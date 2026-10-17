/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// The visible/busy gating pattern is adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/smartalbum/SmartAlbumIndexingCoordinator.kt (Apache-2.0). Modified for Retriever: the visible and busy flags gate a shared single-flight index batch, its charging policy and the model while a search runs; model selection, fallback, search and browse are added around them.

package app.retriever.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlin.time.Duration

data class IndexState(
    val running: Boolean = false,
    val processed: Int = 0,
    val failed: Int = 0,
    val pendingAtStart: Long = 0,
    val current: String? = null,
    val lastStop: StopReason? = null,
    val lastError: String? = null,
    /** Moving average over the current batch. */
    val filesPerMinute: Double = 0.0,
    val etaMinutes: Int? = null,
    val build: ModelBuild? = null,
    val backend: Backend? = null,
    /** Part of the model running elsewhere than [backend], e.g. the vision encoder on CPU. */
    val detail: String? = null,
)

/** When the platform should run the next [Retriever.indexBatch]: in 10 s, in 15 min, on power, when idle, or not until something changes. */
enum class NextRun { SOON, AFTER_COOLDOWN, WHEN_CHARGING, WHEN_IDLE, DONE }

/** The app's one entry point. The constructor touches neither the database nor the model. */
class Retriever(
    private val store: Store,
    source: FileSource,
    private val extractor: Extractor,
    private val factory: EmbedderFactory,
    private val monitor: DeviceMonitor,
    private val hasher: ContentHasher,
    val probe: DeviceProbe,
    private val modelReady: (ModelBuild) -> Boolean,
    private val platformReady: () -> Boolean,
    private val log: (String, Throwable?) -> Unit,
    private val now: () -> Long,
) {
    private val variant = ModelBuilds.variantFor(probe)
    private val sync = LibrarySync(store, source, now)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _requiredBuild = MutableStateFlow(ModelBuilds.buildFor(variant, probe, emptySet()))

    /** The one file platforms download. Settled by [refreshSetUp], which reads past NPU failures. */
    val requiredBuild: StateFlow<ModelBuild> = _requiredBuild.asStateFlow()

    private val _state = MutableStateFlow(IndexState())
    val state: StateFlow<IndexState> = _state.asStateFlow()

    private val _setUp = MutableStateFlow<Boolean?>(null)
    val setUp: StateFlow<Boolean?> = _setUp.asStateFlow()

    private val _prefs = MutableStateFlow(Prefs())
    val prefs: StateFlow<Prefs> = _prefs.asStateFlow()

    /** A visible app indexes now, on battery too; the user is watching it happen. */
    @Volatile
    var uiVisible: Boolean = false

    private val uiBusy = MutableStateFlow(false)
    private val searching = MutableStateFlow(0)

    @Volatile
    private var changedSinceSync = false

    @Volatile
    private var batchJob: Job? = null
    private var lastFileAt = 0L

    private val indexLock = Mutex()
    private val embedderLock = Mutex()
    private var embedder: Embedder? = null
    private var embedderKey: EmbedderKey? = null

    /** Outlives any one embedder, so a reopen keeps the loaded vector table. */
    private var engine: SearchEngine? = null

    /** Only what the model loads with: a prefs switch (kinds, charging) must not reopen it under a running batch. */
    private data class EmbedderKey(val build: ModelBuild, val backend: Backend, val visionTokens: Int, val maxTokens: Int)

    /** [cb] runs on a background thread; Swift hops to the main actor itself. */
    fun observeState(cb: (IndexState) -> Unit): AutoCloseable {
        val job = scope.launch { state.collect { cb(it) } }
        return object : AutoCloseable {
            override fun close() = job.cancel()
        }
    }

    @Throws(Throwable::class)
    suspend fun refreshSetUp() = withContext(Dispatchers.Default) { reload() }

    private fun reload() {
        _prefs.value = store.prefs()
        _requiredBuild.value = ModelBuilds.buildFor(variant, probe, npuFailed())
        _setUp.value = modelReady(_requiredBuild.value) && store.profile() != null && platformReady()
    }

    fun setUiBusy(busy: Boolean) {
        uiBusy.value = busy
    }

    /** Files changed (a MediaStore or PhotoKit notification): the running batch syncs again when it ends. */
    fun notifyChanged() {
        changedSinceSync = true
    }

    /** Stops the running batch within one file; that [indexBatch] returns [NextRun.SOON]. */
    fun cancelBatch() {
        batchJob?.cancel()
    }

    @Throws(Throwable::class)
    suspend fun pause() = updatePrefs { it.copy(paused = true) }

    @Throws(Throwable::class)
    suspend fun resume() = updatePrefs { it.copy(paused = false) }

    @Throws(Throwable::class)
    suspend fun updatePrefs(t: (Prefs) -> Prefs) {
        withContext(Dispatchers.Default) { _prefs.value = store.updatePrefs(t) }
    }

    fun counts(): Flow<Store.Counts> = store.counts()

    @Throws(Throwable::class)
    suspend fun indexBatch(budget: Duration): NextRun {
        if (!indexLock.tryLock()) {
            changedSinceSync = true
            return NextRun.DONE
        }
        try {
            return coroutineScope {
                val work = async(Dispatchers.Default) { runBatch(budget) }
                batchJob = work
                try {
                    work.await()
                } catch (e: CancellationException) {
                    ensureActive() // the caller was cancelled, not just this batch
                    _state.update { it.copy(running = false, current = null, lastStop = StopReason.BUDGET) }
                    NextRun.SOON
                }
            }
        } finally {
            batchJob = null
            indexLock.unlock()
        }
    }

    private suspend fun runBatch(budget: Duration): NextRun {
        changedSinceSync = false
        _state.update { IndexState(running = true, lastStop = it.lastStop, build = it.build, backend = it.backend, detail = it.detail) }
        var stop: StopReason? = null
        var error: String? = null
        var used: Embedder? = null
        var build: ModelBuild? = null
        try {
            sync.sync()
            val (e, b) = embedder() ?: return NextRun.DONE
            used = e
            build = b
            if (store.signature() != e.signature) {
                log("Model signature changed from ${store.signature()?.value} to ${e.signature.value}; indexing everything again", null)
                store.resetForModel(e.signature)
            }
            val stored = store.profile() ?: return NextRun.DONE
            val prefs = store.prefs()
            val device = monitor.state()
            _state.update { it.copy(pendingAtStart = store.countsByState()[FileState.PENDING] ?: 0L) }
            lastFileAt = now()
            val result = IndexEngine(
                store,
                extractor,
                e,
                monitor,
                // Read per file, so a switch flipped mid-batch applies at once.
                settings = {
                    val p = store.prefs()
                    IndexSettings(p.effective(store.profile() ?: stored), p.kinds, p.paused)
                },
                onFile = ::onFile,
                busy = { monitor.state().screenOn && !uiVisible },
                awaitUi = ::awaitUi,
            ).runBatch(
                budget,
                requireCharging = !uiVisible && prefs.effective(stored).bulkOnlyWhenCharging,
                deepen = device.charging && !device.screenOn,
            )
            stop = result.stopReason
            return nextRunFor(result.stopReason, changedSinceSync)
        } catch (e: CancellationException) {
            throw e
        } catch (e: EmbedderClosedException) {
            log("The model closed mid-batch", e)
            // A failed NPU build waits for the generic download; anything else reopens and carries on.
            return if (embedderBroke(used, build, e) && build?.isNpu == true) NextRun.DONE else NextRun.SOON
        } catch (e: Exception) {
            log("Indexing failed", e)
            error = "Something went wrong reading your files. Retriever will try again."
            return NextRun.AFTER_COOLDOWN
        } finally {
            _state.update { it.copy(running = false, current = null, lastStop = stop ?: it.lastStop, lastError = error) }
        }
    }

    private fun onFile(m: FileMetric) {
        if (!m.ok) log("Couldn't index ${m.file.kind} ${m.file.name} (${m.file.mime}, ${m.file.size} B): ${m.error}", m.cause)
        val t = now()
        val rate = 60_000.0 / (t - lastFileAt).coerceAtLeast(1)
        lastFileAt = t
        _state.update {
            val fpm = if (it.filesPerMinute == 0.0) rate else 0.8 * it.filesPerMinute + 0.2 * rate
            val left = (it.pendingAtStart - it.processed - it.failed - 1).coerceAtLeast(0)
            it.copy(
                processed = it.processed + if (m.ok) 1 else 0,
                failed = it.failed + if (m.ok) 0 else 1,
                current = m.file.name,
                filesPerMinute = fpm,
                etaMinutes = ceil(left / fpm).toInt(),
            )
        }
    }

    private suspend fun awaitUi() {
        currentCoroutineContext().ensureActive()
        combine(uiBusy, searching) { busy, n -> !busy && n == 0 }.first { it }
    }

    // --- Model ---

    /** One embedder for indexing and search: two copies of the model would double memory. Null when not set up. */
    private suspend fun embedder(): Pair<Embedder, ModelBuild>? = embedderLock.withLock {
        withContext(Dispatchers.Default) { openLocked()?.let { it to embedderKey!!.build } }
    }

    private suspend fun openLocked(): Embedder? {
        reload()
        checkUnfinishedOpens()
        val build = requiredBuild.value
        val stored = store.profile()
        if (!modelReady(build) || stored == null) return null
        val backend = factory.backendNow(stored)
        val key = EmbedderKey(build, backend, stored.visionTokens, stored.maxTokens)
        embedder?.let { if (embedderKey == key) return it }
        closeLocked()
        val e = try {
            countedOpen {
                factory.open(stored.copy(backend = backend), build).also { opened ->
                    try {
                        opened.embedRaw(listOf(Part.Text(Prefix.query("warm up"))))
                    } catch (t: Throwable) {
                        opened.close()
                        throw t
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            if (!build.isNpu) throw t
            markNpuFailed(build, t)
            return null
        }
        embedder = e
        embedderKey = key
        engine?.embedder = e
        _state.update { it.copy(build = build, backend = e.backend, detail = e.detail) }
        return e
    }

    /**
     * Counts an open that may die natively (SIGSEGV, LMK) in the store. Success, a throw and a cancel all
     * reset it, so only a process that never came back leaves it counted.
     */
    private inline fun <T> countedOpen(open: () -> T): T {
        store.setMeta(UNFINISHED_OPENS, (unfinishedOpens() + 1).toString())
        try {
            return open()
        } finally {
            store.setMeta(UNFINISHED_OPENS, "0")
        }
    }

    /** Opens that never came back crashed the process: stop using that build, or the GPU. */
    // ponytail: the counter doesn't record the backend, so a native crash during a background CPU open (iOS) is
    // blamed on the GPU too; store the backend with the count if that ever shows up in the field.
    private fun checkUnfinishedOpens() {
        if (!ModelBuilds.shouldFallBack(unfinishedOpens())) return
        store.setMeta(UNFINISHED_OPENS, "0")
        val build = requiredBuild.value
        if (build.isNpu) {
            markNpuFailed(build, null)
            return
        }
        log("The model never finished opening twice; using CPU", null)
        store.setMeta(GPU_UNSAFE, "1")
        store.profile()?.takeIf { it.backend == Backend.GPU }?.let { store.setProfile(it.copy(backend = Backend.CPU)) }
    }

    /**
     * [used] threw [EmbedderClosedException]. If it is still the shared embedder, its backend broke (it closed
     * itself): retire it and fall back, NPU build to generic or GPU to CPU. If not, release() or a reopen
     * closed it, which is no failure. True when it broke.
     */
    private suspend fun embedderBroke(used: Embedder?, build: ModelBuild?, cause: Throwable): Boolean = embedderLock.withLock {
        if (used == null || build == null || embedder !== used) return@withLock false
        // The backend [used] was opened on: a CPU embedder from a background task failing says nothing about the GPU.
        val onGpu = embedderKey?.backend == Backend.GPU
        closeLocked()
        if ((cause as? EmbedderClosedException)?.broken == false) {
            log("The model can't run right now; it reopens on the next batch", cause)
            return@withLock false
        }
        if (build.isNpu) {
            markNpuFailed(build, cause)
        } else if (onGpu) {
            store.profile()?.takeIf { it.backend == Backend.GPU }?.let {
                log("GPU inference failed; using CPU from now on", cause)
                store.setProfile(it.copy(backend = Backend.CPU))
            }
        }
        true
    }

    /** This phone's NPU build failed, so it runs the generic one. Reads the store; call off the main thread. */
    fun fellBack(): Boolean = npuFailed().any { name -> ModelBuilds.npu.any { it.fileName == name && it.variant == variant } }

    private fun unfinishedOpens(): Int = store.meta(UNFINISHED_OPENS)?.toIntOrNull() ?: 0

    private fun npuFailed(): Set<String> =
        store.meta(NPU_FAILED)?.let { runCatching { Json.decodeFromString<Set<String>>(it) }.getOrNull() } ?: emptySet()

    /** The chip's build can't run here: require the generic file from now on, for this and every later launch. */
    private fun markNpuFailed(build: ModelBuild, cause: Throwable?) {
        log("NPU build ${build.fileName} failed; falling back to ${ModelBuilds.fallback(build).fileName}", cause)
        store.setMeta(NPU_FAILED, Json.encodeToString(npuFailed() + build.fileName))
        // ponytail: picks the generic build's first backend uncalibrated; the unfinished-open counter demotes a crashing GPU.
        store.profile()?.takeIf { it.backend == Backend.NPU }?.let {
            store.setProfile(it.copy(backend = factory.backends(ModelBuilds.fallback(build)).first()))
        }
        reload()
    }

    private fun closeLocked() {
        embedder?.close()
        embedder = null
        embedderKey = null
        engine?.embedder = NoModel
    }

    /** Frees the model's memory; the next search or batch opens it again. */
    @Throws(Throwable::class)
    suspend fun release() {
        embedderLock.withLock { withContext(Dispatchers.Default) { closeLocked() } }
    }

    // --- Search ---

    @Throws(Throwable::class)
    suspend fun search(q: String, kinds: Set<Kind>? = null, limit: Int = 20): SearchEngine.SearchResult =
        searchWith { it.search(q, limit = limit, kinds = kinds) }

    /** [jpeg]: a picked or camera photo, longest side at most 1024 px. Vector lane only. */
    @Throws(Throwable::class)
    suspend fun searchImage(jpeg: ByteArray, kinds: Set<Kind>? = null, limit: Int = 20): SearchEngine.SearchResult =
        searchWith { it.searchImage(jpeg, limit = limit, kinds = kinds) }

    private suspend fun <T> searchWith(run: suspend (SearchEngine) -> T): T {
        searching.update { it + 1 }
        try {
            return withContext(Dispatchers.Default) {
                val (engine, used, build) = engine()
                try {
                    run(engine)
                } catch (e: EmbedderClosedException) {
                    log("The model closed during a search; keyword results only", e)
                    embedderBroke(used, build, e)
                    run(SearchEngine(store, NoModel))
                }
            }
        } finally {
            searching.update { it - 1 }
        }
    }

    /** The search engine with the embedder it uses and that embedder's build (both null: keyword-only). */
    private suspend fun engine(): Triple<SearchEngine, Embedder?, ModelBuild?> {
        // The model is opening (seconds) or Tune is measuring: answer from keywords now instead of waiting.
        if (!embedderLock.tryLock()) return Triple(SearchEngine(store, NoModel), null, null)
        try {
            val e = try {
                openLocked()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Exception) {
                log("Couldn't open the model; keyword results only", t)
                null
            }
            val s = engine ?: SearchEngine(store, e ?: NoModel).also { engine = it }
            return Triple(s, e, e?.let { embedderKey?.build })
        } finally {
            embedderLock.unlock()
        }
    }

    /** Loads the vector table off the search path, e.g. when the app comes to the front. */
    @Throws(Throwable::class)
    suspend fun warmSearch() {
        io { embedderLock.withLock { engine ?: SearchEngine(store, embedder ?: NoModel).also { engine = it } }.refresh() }
    }

    @Throws(Throwable::class)
    suspend fun recordQuery(q: String) = io { store.addRecentQuery(q, now()) }

    @Throws(Throwable::class)
    suspend fun recentQueries(): List<String> = io { store.recentQueries() }

    @Throws(Throwable::class)
    suspend fun removeRecentQuery(q: String) = io { store.removeRecentQuery(q) }

    @Throws(Throwable::class)
    suspend fun clearRecentQueries() = io { store.clearRecentQueries() }

    // --- Browse ---

    @Throws(Throwable::class)
    suspend fun files(kind: Kind?, folder: String?, sort: Store.Sort, limit: Int, offset: Int): List<Store.StoredFile> =
        io { store.files(kind, folder, sort, limit, offset) }

    @Throws(Throwable::class)
    suspend fun folders(): List<Pair<String, Long>> = io { store.folders() }

    @Throws(Throwable::class)
    suspend fun duplicates(minSize: Long): List<DuplicateGroup> = io { DuplicateFinder(store, hasher).find(minSize) }

    @Throws(Throwable::class)
    suspend fun skipped(limit: Int): List<Store.StoredFile> = io { store.skipped(limit) }

    @Throws(Throwable::class)
    suspend fun retrySkipped() = io { store.retrySkipped() }

    @Throws(Throwable::class)
    suspend fun requeue(kinds: Set<Kind>) = io { store.requeue(kinds) }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.Default) { block() }

    // --- Calibration ---

    @Throws(Throwable::class)
    suspend fun calibrate(samples: Samples, tuneAgain: Boolean): CalibrationResult {
        cancelBatch()
        // Holding the model lock keeps searches keyword-only meanwhile, so two copies of the model never load.
        return indexLock.withLock {
            embedderLock.withLock {
                io {
                    closeLocked()
                    calibrateLocked(samples, tuneAgain)
                }
            }
        }
    }

    private suspend fun calibrateLocked(samples: Samples, tuneAgain: Boolean): CalibrationResult {
        reload()
        checkUnfinishedOpens()
        val build = requiredBuild.value
        val stored = store.profile()
        // A GPU that crashed natively twice is not measured again, so Tune can finish on CPU.
        val measured = if (store.meta(GPU_UNSAFE) == null) {
            factory
        } else {
            object : EmbedderFactory by factory {
                override fun backends(build: ModelBuild) = factory.backends(build) - Backend.GPU
            }
        }
        val result = try {
            countedOpen { Calibration(measured).run(samples, build, probe.ramGb, stored?.visionTokens ?: 70) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (build.isNpu) markNpuFailed(build, e)
            throw e
        }
        val profile = if (tuneAgain && stored != null) result.profile.copy(visionTokens = stored.visionTokens) else result.profile
        store.setProfile(profile)
        if (!store.hasPrefs()) store.updatePrefs { Prefs.defaultFor(profile) }
        reload()
        return result.copy(profile = profile)
    }

    /** Stands in when no model can open, so search still answers from keywords (its signature never matches). */
    private object NoModel : Embedder {
        override val signature = Signature("none")
        override val backend = Backend.CPU

        override suspend fun embedRaw(parts: List<Part>): FloatArray = throw EmbedderClosedException("no model")

        override fun close() {}
    }

    internal companion object {
        const val NPU_FAILED = "npu_failed"
        const val UNFINISHED_OPENS = "unfinished_opens"
        const val GPU_UNSAFE = "gpu_unsafe"

        fun nextRunFor(reason: StopReason, changedSinceSync: Boolean): NextRun = when (reason) {
            StopReason.BUDGET -> NextRun.SOON
            StopReason.HOT -> NextRun.AFTER_COOLDOWN
            StopReason.UNPLUGGED -> NextRun.WHEN_CHARGING
            StopReason.BUSY -> NextRun.WHEN_IDLE
            StopReason.DONE -> if (changedSinceSync) NextRun.SOON else NextRun.DONE
            StopReason.PAUSED -> NextRun.DONE
        }
    }
}
