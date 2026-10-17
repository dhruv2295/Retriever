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

import kotlinx.coroutines.Job
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

/** Home's facts besides the counts: the newest documents and screenshots, folders, large files, files read by name only. */
data class HomeFacts(
    val newDocs: List<SourceFile>,
    val newShots: List<SourceFile>,
    val folders: Long,
    val largeCount: Long,
    val largeBytes: Long,
    val nameOnly: Long,
)

/** What a phone with too little memory for the model reads inside: the words in screenshots, PDFs and documents. */
val textOnlyKinds: Set<Kind> = setOf(Kind.SCREENSHOT, Kind.PDF, Kind.DOC)

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
    /** The local zone's offset from UTC now, so "yesterday" means the person's yesterday. No default: a platform must pass it. */
    private val zoneOffsetMs: () -> Long,
    private val dispatchers: CoreDispatchers = CoreDispatchers(),
) {
    private val variant = ModelBuilds.variantFor(probe)

    /** Too little memory for the model: file names and text are indexed for keyword search, nothing is embedded. */
    val textOnly: Boolean = Tiers.textOnly(probe.ramGb)

    /** What text-only mode reads with: no model, no video frames, no audio. */
    private val textOnlyProfile = DeviceProfile(Tier.LIGHT, variant, Backend.CPU, 70, 0, false, false)
    private val sync = LibrarySync(store, source, now)

    private val _requiredBuild = MutableStateFlow(ModelBuilds.buildFor(variant, probe, emptySet()))

    /** The one file platforms download. Settled by [refreshSetUp], which reads past NPU failures. */
    val requiredBuild: StateFlow<ModelBuild> = _requiredBuild.asStateFlow()

    private val _state = MutableStateFlow(IndexState())
    val state: StateFlow<IndexState> = _state.asStateFlow()

    private val _setUp = MutableStateFlow<Boolean?>(null)
    val setUp: StateFlow<Boolean?> = _setUp.asStateFlow()

    private val _prefs = MutableStateFlow(Prefs())
    val prefs: StateFlow<Prefs> = _prefs.asStateFlow()

    private val _modelMissing = MutableStateFlow(false)

    /** Set up, but the model file is gone (freed, or a fallback build not downloaded yet): names and words search only. */
    val modelMissing: StateFlow<Boolean> = _modelMissing.asStateFlow()

    private val _starred = MutableStateFlow<Set<String>>(emptySet())

    /** Starred URIs, so a row's star is one lookup. */
    val starred: StateFlow<Set<String>> = _starred.asStateFlow()

    @Volatile
    private var lastNameOnly = 0L

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
    private var leftByKind = mutableMapOf<Kind, Long>()
    private val msByKind = mutableMapOf<Kind, Double>()

    private val indexLock = Mutex()
    private val embedderLock = Mutex()
    private var embedder: Embedder? = null
    private var embedderKey: EmbedderKey? = null

    /** Outlives any one embedder, so a reopen keeps the loaded vector table. */
    private var engine: SearchEngine? = null

    /** Only what the model loads with: a prefs switch (kinds, charging) must not reopen it under a running batch. */
    private data class EmbedderKey(val build: ModelBuild, val backend: Backend, val visionTokens: Int, val maxTokens: Int)

    @Throws(Throwable::class)
    suspend fun refreshSetUp() = withContext(dispatchers.default) {
        reload()
        _starred.value = store.starredUris()
    }

    private fun reload() {
        _prefs.value = store.prefs()
        _requiredBuild.value = ModelBuilds.buildFor(variant, probe, npuFailed())
        // Tune writes the profile with the model present, so it marks a finished setup; losing the file later keeps it.
        val tuned = store.profile() != null
        _modelMissing.value = !textOnly && tuned && !modelReady(_requiredBuild.value)
        _setUp.value = platformReady() && (textOnly || tuned)
    }

    private fun noModel() = textOnly || _modelMissing.value

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
        withContext(dispatchers.default) {
            // "Stop searching" acts here, paused or not: every platform's switch lands in this one place.
            var before = Prefs()
            val new = store.updatePrefs { old ->
                before = old
                t(old).also {
                    if (old.rememberSearches && !it.rememberSearches) store.clearRecentQueries()
                    (old.kinds - it.kinds).takeIf { k -> k.isNotEmpty() }?.let(store::deleteChunksOfKinds)
                    (it.kinds - old.kinds).takeIf { k -> k.isNotEmpty() }?.let(store::requeue)
                }
            }
            _prefs.value = new
            // Stars of dropped files stay, hidden until the folder is put back.
            if (!before.excludedFolders.containsAll(new.excludedFolders) && sync.dropExcluded(new) > 0) {
                _starred.value = store.starredUris()
            }
        }
    }

    /** Without the model nothing waits for a vector, so the deepen counts read zero. */
    fun counts(): Flow<Store.Counts> = combine(store.counts(), modelMissing) { c, missing ->
        if (textOnly || missing) c.copy(tailChunks = 0, tailFiles = 0) else c
    }

    /** The files among [fileIds] that search can't reach in full; files read whole are left out. */
    @Throws(Throwable::class)
    suspend fun readExtents(fileIds: List<Long>): Map<Long, ReadExtent> = io {
        store.readExtents(fileIds).let { m -> if (noModel()) m.filterValues { it != ReadExtent.DEEPENING } else m }
    }

    /**
     * [deepen] false reads pending files only, so a batch with none left ends DONE instead of embedding long files'
     * later parts. [waitForLock]: wait for a running batch (being cancelled) instead of returning DONE at once.
     */
    @Throws(Throwable::class)
    suspend fun indexBatch(budget: Duration, deepen: Boolean = true, waitForLock: Boolean = false): NextRun {
        if (waitForLock) {
            indexLock.lock()
        } else if (!indexLock.tryLock()) {
            changedSinceSync = true
            return NextRun.DONE
        }
        try {
            return coroutineScope {
                val work = async(dispatchers.default) { runBatch(budget, deepen) }
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

    private suspend fun runBatch(budget: Duration, mayDeepen: Boolean): NextRun {
        changedSinceSync = false
        _state.update { IndexState(running = true, lastStop = it.lastStop, build = it.build, backend = it.backend, detail = it.detail) }
        var stop: StopReason? = null
        var error: String? = null
        var used: Embedder? = null
        var build: ModelBuild? = null
        try {
            // Stars follow their files: hidden while a file is gone, back when it returns.
            sync.sync().let { if (it.removed > 0 || it.added > 0) _starred.value = store.starredUris() }
            val e = if (textOnly) {
                null
            } else {
                // A missing model file returns here: the sync kept names current, and files wait to be read in full.
                val (opened, b) = embedder() ?: return NextRun.DONE
                used = opened
                build = b
                if (store.signature() != opened.signature) {
                    log("Model signature changed from ${store.signature()?.value} to ${opened.signature.value}; indexing everything again", null)
                    store.resetForModel(opened.signature)
                }
                opened
            }
            val stored = if (textOnly) textOnlyProfile else store.profile() ?: return NextRun.DONE
            val device = monitor.state()
            val pending = store.pendingByKind()
            leftByKind = pending.toMutableMap()
            msByKind.clear()
            _state.update { it.copy(pendingAtStart = pending.values.sum()) }
            lastFileAt = now()
            val result = IndexEngine(
                store,
                extractor,
                e,
                monitor,
                // Read per file, so a switch flipped mid-batch applies at once.
                settings = {
                    val p = batchPrefs()
                    IndexSettings(p.effective(if (textOnly) textOnlyProfile else store.profile() ?: stored), p.kinds, p.paused)
                },
                onFile = ::onFile,
                busy = { monitor.state().screenOn && !uiVisible },
                awaitUi = ::awaitUi,
            ).runBatch(
                budget,
                requireCharging = !uiVisible && batchPrefs().effective(stored).bulkOnlyWhenCharging,
                // An open app deepens once nothing is pending; in the background only on the charger with the screen off.
                deepen = mayDeepen && !textOnly && (uiVisible || (device.charging && !device.screenOn)),
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

    /** Text-only mode reads screenshots, PDFs and documents; photos, video and audio stay findable by name. */
    private fun batchPrefs(): Prefs = store.prefs().let { if (textOnly) it.copy(kinds = it.kinds intersect textOnlyKinds) else it }

    private fun onFile(m: FileMetric) {
        retierFrom(m)
        if (!m.ok) log("Couldn't index ${m.file.kind} (${m.file.mime}, ${m.file.size} B): ${m.cause?.let { it::class.simpleName }}", m.cause)
        else log("Indexed ${m.file.kind}: extract ${m.extractMs.toInt()} ms, embed ${m.embedMs.toInt()} ms, ${m.pieces} pieces", null)
        val t = now()
        val gapMs = (t - lastFileAt).coerceAtLeast(1)
        lastFileAt = t
        val kind = m.file.kind
        // A kind's own pace: photos run fast and video slow, so one overall rate misjudges the video still to come.
        msByKind[kind] = msByKind[kind]?.let { 0.8 * it + 0.2 * gapMs } ?: gapMs.toDouble()
        leftByKind[kind] = ((leftByKind[kind] ?: 0L) - 1).coerceAtLeast(0)
        _state.update {
            val rate = 60_000.0 / gapMs
            val fpm = if (it.filesPerMinute == 0.0) rate else 0.8 * it.filesPerMinute + 0.2 * rate
            it.copy(
                processed = it.processed + if (m.ok) 1 else 0,
                failed = it.failed + if (m.ok) 0 else 1,
                current = m.file.name,
                filesPerMinute = fpm,
                etaMinutes = etaMinutes(leftByKind, msByKind, 60_000.0 / fpm),
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
        withContext(dispatchers.default) { openLocked()?.let { it to embedderKey!!.build } }
    }

    private suspend fun openLocked(): Embedder? {
        reload()
        if (textOnly) return null
        checkUnfinishedOpens()
        val build = requiredBuild.value
        val stored = store.profile()
        if (!modelReady(build) || stored == null) {
            closeLocked() // the file went away under an open model: stop answering by meaning, as a reopen would
            return null
        }
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
        factory.keepOnly(e)
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
        embedderLock.withLock { withContext(dispatchers.default) { closeLocked() } }
    }

    // --- Search ---

    // Swift sees no default arguments, so its three-argument calls keep this overload.
    @Throws(Throwable::class)
    suspend fun search(q: String, kinds: Set<Kind>?, limit: Int): SearchEngine.SearchResult = search(q, kinds, limit, false)

    /**
     * [q] is parsed: kind words and dates become filters on top of the UI's [kinds]. [literal] searches the words as typed.
     * [onlyStarred] keeps starred files only.
     */
    @Throws(Throwable::class)
    suspend fun search(
        q: String,
        kinds: Set<Kind>? = null,
        limit: Int = 20,
        onlyStarred: Boolean = false,
        literal: Boolean = false,
    ): SearchEngine.SearchResult = searchWith { s -> parsedSearch(q, kinds, limit, onlyStarred, literal, relax = true) { s.search(it) } }

    /**
     * Phase one, as the user types: names only, from memory once [warmSearch] built the name index; no model. Never
     * relaxed: names alone finding nothing under a filter says nothing about what the full search will find.
     */
    @Throws(Throwable::class)
    suspend fun searchNames(
        q: String,
        kinds: Set<Kind>? = null,
        limit: Int = 20,
        onlyStarred: Boolean = false,
        literal: Boolean = false,
    ): SearchEngine.SearchResult = io { parsedSearch(q, kinds, limit, onlyStarred, literal, relax = false) { newEngine(NoModel).names(it) } }

    /**
     * Searches with the filters [q] was understood to ask for. When they leave nothing and [relax], searches once more
     * without them, keeping the UI's kinds and the starred scope, and marks the result relaxed.
     */
    private suspend fun parsedSearch(
        q: String,
        uiKinds: Set<Kind>?,
        limit: Int,
        onlyStarred: Boolean,
        literal: Boolean,
        relax: Boolean,
        run: suspend (SearchRequest) -> SearchEngine.SearchResult,
    ): SearchEngine.SearchResult {
        val p = if (literal) ParsedQuery(q.trim()) else QueryParser.parse(q, now(), zoneOffsetMs())
        val ids = starredIds(onlyStarred)
        // Nothing to search, so nothing by meaning is missing.
        if (ids != null && ids.isEmpty()) return SearchEngine.SearchResult(emptyList(), vectorLane = true, parsed = p)
        val parsedKinds = p.kinds.ifEmpty { null }
        val kinds = if (parsedKinds == null) uiKinds else uiKinds?.intersect(parsedKinds) ?: parsedKinds
        val req = SearchRequest(p.text, kinds, p.from, p.to, ids, limit, onlyStarred = onlyStarred)
        val filtered = parsedKinds != null || p.from != null
        val search: suspend (SearchRequest) -> SearchEngine.SearchResult = if (p.text.isBlank()) ::browse else run
        if (p.text.isBlank() && !filtered) return SearchEngine.SearchResult(emptyList(), vectorLane = true, parsed = p)
        val first = search(req)
        if (first.hits.isNotEmpty() || !filtered || !relax) return first.copy(parsed = p)
        return search(req.copy(kinds = uiKinds, from = null, to = null)).copy(parsed = p, relaxed = true)
    }

    private fun starredIds(onlyStarred: Boolean): Set<Long>? =
        if (onlyStarred) store.starredFiles(Int.MAX_VALUE).mapTo(HashSet()) { it.id } else null

    /** A query that was all filters ("screenshots last week"): the newest files that pass them, filtered in SQL. */
    private fun browse(req: SearchRequest): SearchEngine.SearchResult {
        val hits = store.captured(req.kinds ?: Kind.entries.toSet(), req.from ?: Long.MIN_VALUE, req.to ?: Long.MAX_VALUE, req.onlyStarred, req.limit)
            .map { SearchEngine.Hit(it.file, it.id, 0L, "", null, 0.0, false) }
        // No words were left to match by meaning, so nothing is missing: the screen must not say meaning results are on the way.
        return SearchEngine.SearchResult(hits, vectorLane = true)
    }

    private fun newEngine(e: Embedder) = SearchEngine(store, e, nameIndex = { sync.names })

    /** [jpeg]: a picked or camera photo, longest side at most 1024 px. Vector lane only. [onlyStarred] keeps starred files only. */
    @Throws(Throwable::class)
    suspend fun searchImage(jpeg: ByteArray, kinds: Set<Kind>? = null, limit: Int = 20, onlyStarred: Boolean = false): SearchEngine.SearchResult {
        val ids = io { starredIds(onlyStarred) }
        if (ids != null && ids.isEmpty()) return SearchEngine.SearchResult(emptyList(), vectorLane = true)
        return searchWith { it.searchImage(jpeg, limit = limit, kinds = kinds, fileIds = ids) }
    }

    private suspend fun <T> searchWith(run: suspend (SearchEngine) -> T): T {
        searching.update { it + 1 }
        try {
            return withContext(dispatchers.default) {
                val (engine, used, build) = engine()
                try {
                    run(engine)
                } catch (e: EmbedderClosedException) {
                    log("The model closed during a search; keyword results only", e)
                    embedderBroke(used, build, e)
                    run(newEngine(NoModel))
                }
            }
        } finally {
            searching.update { it - 1 }
        }
    }

    /** The search engine with the embedder it uses and that embedder's build (both null: keyword-only). */
    private suspend fun engine(): Triple<SearchEngine, Embedder?, ModelBuild?> {
        // The model is opening (seconds) or Tune is measuring: answer from keywords now instead of waiting.
        if (!embedderLock.tryLock()) return Triple(newEngine(NoModel), null, null)
        try {
            val e = try {
                openLocked()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Exception) {
                log("Couldn't open the model; keyword results only", t)
                null
            }
            val s = engine ?: newEngine(e ?: NoModel).also { engine = it }
            return Triple(s, e, e?.let { embedderKey?.build })
        } finally {
            embedderLock.unlock()
        }
    }

    /**
     * Readies search off its path, e.g. when the app comes to the front or the search bar takes focus: the name index,
     * the vector table and, once set up, the model. Searches meanwhile answer from keywords instead of waiting.
     * The first warm-up after the schema 3 upgrade also refills the keyword tables, last, so names and the model are
     * ready first; one warm-up refills them however many run at once.
     */
    @Throws(Throwable::class)
    suspend fun warmSearch() = io {
        // Reads only `files`, which the upgrade left whole, so names answer while the keyword tables are still empty.
        sync.loadNames()
        val s = embedderLock.withLock { engine ?: newEngine(embedder ?: NoModel).also { engine = it } }
        coroutineScope {
            launch { s.refresh() }
            if (_setUp.value == true && !textOnly) {
                embedderLock.withLock {
                    try {
                        openLocked()
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Exception) {
                        log("Couldn't open the model ahead of a search", t)
                    }
                }
            }
        }
        if (ftsRefill.tryLock()) {
            try {
                store.rebuildFtsIfPending()
            } finally {
                ftsRefill.unlock()
            }
        }
    }

    private val ftsRefill = Mutex()

    /** Does nothing while the person has turned remembering searches off. */
    @Throws(Throwable::class)
    suspend fun recordQuery(q: String) = io { if (store.prefs().rememberSearches) store.addRecentQuery(q, now()) }

    @Throws(Throwable::class)
    suspend fun recentQueries(): List<String> = io { store.recentQueries() }

    @Throws(Throwable::class)
    suspend fun removeRecentQuery(q: String) = io { store.removeRecentQuery(q) }

    @Throws(Throwable::class)
    suspend fun clearRecentQueries() = io { store.clearRecentQueries() }

    // --- Starred ---

    /** Starring a file that isn't in the index does nothing. */
    @Throws(Throwable::class)
    suspend fun setStarred(uri: String, on: Boolean) = io {
        store.setStarred(uri, on, now())
        _starred.value = store.starredUris()
    }

    /** Starred files still in the index, most recently starred first. */
    @Throws(Throwable::class)
    suspend fun starredFiles(limit: Int = 200, offset: Int = 0): List<Store.StoredFile> = io { store.starredFiles(limit, offset) }

    // --- Home ---

    @Throws(Throwable::class)
    suspend fun home(): HomeFacts = io {
        val (largeCount, largeBytes) = store.largeFiles(LARGE_FILE)
        // Reads every file's chunks, and only the caught-up line shows it: counted once nothing waits, else the last count.
        if ((store.countsByState()[FileState.PENDING] ?: 0L) == 0L) lastNameOnly = store.nameOnlyCount()
        HomeFacts(
            newDocs = store.newestFiles(setOf(Kind.PDF, Kind.DOC), 3).map { it.file },
            newShots = store.files(Kind.SCREENSHOT, limit = 10).map { it.file },
            folders = store.folderCount(),
            largeCount = largeCount,
            largeBytes = largeBytes,
            nameOnly = lastNameOnly,
        )
    }

    /**
     * Up to three "Try searching" examples, each shown once a kind it would find is read in full (some files, none
     * waiting) and read inside at all ([readInside]). The photo one needs the model, as photos are found by what they show.
     */
    fun suggestions(byKindState: Map<Pair<Kind, FileState>, Long>): List<String> {
        val inside = readInside()
        fun ready(k: Kind) = k in inside && (k != Kind.PHOTO || !noModel()) && (byKindState[k to FileState.PENDING] ?: 0L) == 0L &&
            byKindState.any { (key, n) -> key.first == k && n > 0 }
        return EXAMPLES.filter { (_, kinds) -> kinds.any(::ready) }.take(3).map { it.first }
    }

    /** The kinds whose insides are read: the Settings switches, cut to what this phone can read. Others are found by name. */
    fun readInside(): Set<Kind> = _prefs.value.kinds intersect readableKinds

    /** The kinds this phone can read inside, and so the switches Settings shows. */
    val readableKinds: Set<Kind> get() = if (textOnly) textOnlyKinds else Kind.entries.toSet() - Kind.OTHER

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

    private suspend fun <T> io(block: suspend () -> T): T = withContext(dispatchers.default) { block() }

    // --- Calibration ---

    @Throws(Throwable::class)
    suspend fun calibrate(samples: Samples, tuneAgain: Boolean): CalibrationResult {
        check(!textOnly) { "This phone has too little memory for the model, so there is nothing to tune: Retriever searches file names and text." }
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
        applyProfile(stored, profile)
        return result.copy(profile = profile)
    }

    /** Stores [profile]; settings still at [stored]'s defaults take its defaults, and files read under the old ones are read again. */
    private fun applyProfile(stored: DeviceProfile?, profile: DeviceProfile) {
        store.setProfile(profile)
        if (!store.hasPrefs()) {
            store.updatePrefs { Prefs.defaultFor(profile) }
        } else if (stored != null && stored.tier != profile.tier) {
            val old = store.prefs()
            val new = store.updatePrefs { it.retuned(stored, profile) }
            rereadAfterRetune(old, stored, new, profile).takeIf { it.isNotEmpty() }?.let(store::requeue)
        }
        reload()
    }

    private val photoMs = ArrayDeque<Double>()

    /**
     * Calibration times one moment, and whatever else runs then (an install, a sync) only ever slows the model down.
     * So when photos read while indexing are fast enough for a higher tier, the phone moves up. Never down: Tune again
     * does that. Vision tokens stay, or every vector would be stale.
     */
    private fun retierFrom(m: FileMetric) {
        if (textOnly || !m.ok || m.file.kind != Kind.PHOTO || m.pieces != 1 || m.embedMs <= 0) return
        photoMs.addLast(m.embedMs)
        if (photoMs.size > RETIER_WINDOW) photoMs.removeFirst()
        if (photoMs.size < RETIER_WINDOW) return
        val stored = store.profile() ?: return
        val ms = photoMs.sorted()[RETIER_WINDOW / 2]
        val seen = Tiers.profileFor(ms, probe.ramGb, stored.backend, stored.model, stored.maxTokens)
        if (seen.tier >= stored.tier) return
        log("Photos read in ${ms.toInt()} ms each, faster than tuning measured: ${stored.tier} to ${seen.tier}", null)
        photoMs.clear()
        applyProfile(stored, seen.copy(visionTokens = stored.visionTokens))
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
        const val LARGE_FILE = 100L * 1024 * 1024

        /** Remaining files per kind at that kind's measured pace; a kind not yet seen uses [fallbackMs]. */
        fun etaMinutes(left: Map<Kind, Long>, msPerFile: Map<Kind, Double>, fallbackMs: Double): Int =
            ceil(left.entries.sumOf { (k, n) -> n * (msPerFile[k] ?: fallbackMs) } / 60_000).toInt()

        /** Photos whose median read time can move the tier up; an odd quick one alone can't. */
        const val RETIER_WINDOW = 5

        val EXAMPLES = listOf(
            "wifi password" to setOf(Kind.SCREENSHOT),
            "boarding pass" to setOf(Kind.PDF, Kind.SCREENSHOT),
            "electricity bill" to setOf(Kind.PDF),
            "notes from the meeting" to setOf(Kind.DOC),
            "dog on the beach" to setOf(Kind.PHOTO),
        )

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
