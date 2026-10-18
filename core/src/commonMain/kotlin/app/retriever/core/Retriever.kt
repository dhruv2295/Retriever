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
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

data class IndexState(
    val running: Boolean = false,
    val processed: Int = 0,
    val failed: Int = 0,
    val pendingAtStart: Long = 0,
    val current: String? = null,
    /** The file in extraction/embedding now, cleared whenever reading stops. */
    val currentFile: SourceFile? = null,
    /** Successful reads, newest first, at most 24; on restart, reversed plan order intersected with INDEXED. */
    val recentFiles: List<SourceFile> = emptyList(),
    val lastStop: StopReason? = null,
    val lastError: String? = null,
    /** Files per minute of reading this session. */
    val filesPerMinute: Double = 0.0,
    val etaMinutes: Int? = null,
    val build: ModelBuild? = null,
    val backend: Backend? = null,
    /** Part of the model running elsewhere than [backend], e.g. the vision encoder on CPU. */
    val detail: String? = null,
) {
    /** Shared setup copy; null while reading or when there is no pause to explain. */
    val pauseReason: String? get() = if (running) {
        null
    } else {
        when (lastStop) {
            StopReason.BUSY -> "Paused while you use other apps"
            StopReason.POWER_SAVE -> "Paused: Battery Saver"
            StopReason.HOT -> "Paused while your device cools down"
            StopReason.UNPLUGGED -> "Paused until connected to power"
            StopReason.PAUSED -> "Reading paused"
            else -> null
        }
    }
}

/**
 * Home's facts besides the counts: the newest pictures, screenshots and documents, starred files, folders, large files,
 * files read by name only. Never OTHER files: app databases and dumps are not something to show.
 */
data class HomeFacts(
    val newMedia: List<SourceFile>,
    val newShots: List<SourceFile>,
    val newDocs: List<SourceFile>,
    val starred: List<SourceFile>,
    val starredCount: Long,
    val folders: Long,
    val largeCount: Long,
    val largeBytes: Long,
    val nameOnly: Long,
)

/** The photos and videos taken on one calendar day in [year], with a photo for the cover when there is one. */
data class YearMemory(val year: Int, val photos: Long, val videos: Long, val cover: SourceFile)

/** One piece of a file's text, in reading order, for the in-app reader. */
data class ChunkText(val ordinal: Int, val locator: String, val text: String)

/** What a phone with too little memory for the model reads inside: the words in screenshots, PDFs and documents. */
val textOnlyKinds: Set<Kind> = setOf(Kind.SCREENSHOT, Kind.PDF, Kind.DOC)

/**
 * When the platform should run the next [Retriever.indexBatch]: in 10 s, after a thermal rest, on power, when idle,
 * after a failed batch ([RETRY]: both platforms rest as for heat), or not until something changes.
 */
enum class NextRun { SOON, AFTER_COOLDOWN, WHEN_CHARGING, WHEN_IDLE, RETRY, DONE }

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
    /**
     * The process before this one died of a native crash or a signal. Only then does a model open it left unfinished
     * count against the NPU build or the GPU. The default blames every unfinished open.
     */
    lastExitCrashed: () -> Boolean = { true },
    private val frames: FrameSource? = null,
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

    /** The first run's planned ids while only they are read (scope SAMPLE); null reads everything. Mirrors Store meta. */
    private val sampleIds = MutableStateFlow<List<Long>?>(null)

    private fun loadScope(): List<Long>? = store.sampleIds().also { sampleIds.value = it }

    /** The planned ids while only they are read, for a search scoped to them ([search]'s `scope`); null once everything is read. */
    val scopeIds: StateFlow<List<Long>?> = sampleIds.asStateFlow()

    /** The name-only count and the file-state counts it was taken at, so it is recomputed only when those change. */
    @Volatile
    private var nameOnlyAt: Pair<Map<FileState, Long>, Long>? = null

    /** A visible app indexes now, on battery too; the user is watching it happen. Showing drops a pending release. */
    @Volatile
    var uiVisible: Boolean = false
        set(v) {
            field = v
            if (v) releaseWanted = false
        }

    /** A hidden-idle release was asked for and hasn't happened yet: a batch or search held the model. */
    @Volatile
    private var releaseWanted = false

    private val uiBusy = MutableStateFlow(false)
    private val searching = MutableStateFlow(0)

    @Volatile
    private var changedSinceSync = false

    @Volatile
    private var batchJob: Job? = null

    /** Read from the store on first use, so the constructor stays off the database. */
    private val eta by lazy { Eta(SpeedBook.decode(store.meta(SPEED_KEY))).also { savedBook = it.book } }
    private var savedBook: SpeedBook? = null
    private var speedKey = SPEED_KEY
    private var bookSavedAt = 0L

    private val indexLock = Mutex()
    private var batchSoon = false // guarded by indexLock, including the gap before a SOON follow-up

    /** Outlives any one embedder, so a reopen keeps the loaded vector table. Read and set under [model]'s lock. */
    private var engine: SearchEngine? = null

    /** The one embedder for indexing and search. Lock order: [indexLock] first, then its lock. */
    private val model = ModelHost(
        store, factory, variant, textOnly, requiredBuild, modelReady, log, dispatchers,
        reload = ::reload,
        onOpened = { e, build ->
            engine?.embedder = e
            _state.update { it.copy(build = build, backend = e.backend, detail = e.detail) }
        },
        onClosed = { engine?.embedder = NoModel },
        lastExitCrashed = lastExitCrashed,
        visible = { uiVisible },
    )

    /** For the model's open off a search's path: it outlives the search that started it. */
    private val background = CoroutineScope(SupervisorJob() + dispatchers.default)

    @Volatile
    private var opening: Job? = null

    @Throws(Throwable::class)
    suspend fun refreshSetUp() = withContext(dispatchers.default) {
        reload()
        _starred.value = store.starredUris()
    }

    private fun reload() {
        reconcileRecentFiles()
        _prefs.value = store.prefs()
        val ids = loadScope()
        if (_state.value.recentFiles.isEmpty() && ids != null) {
            val indexed = store.indexedIn(ids)
            _state.update { it.copy(recentFiles = ids.asReversed().mapNotNull(indexed::get).take(RECENT_FILES)) }
        }
        _requiredBuild.value = ModelBuilds.buildFor(variant, probe, model.npuFailed())
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
            // Files of a switched-off kind pass by unread in milliseconds: that pace would under-read them once back on.
            (new.kinds - before.kinds).takeIf { it.isNotEmpty() }?.let { k -> eta.book = eta.book.copy(paces = eta.book.paces - k) }
            // Stars of dropped files stay, hidden until the folder is put back.
            if (!before.excludedFolders.containsAll(new.excludedFolders) && sync.dropExcluded(new) > 0) {
                _starred.value = store.starredUris()
                reconcileRecentFiles()
            }
            if (new.paused) releaseIfIdle()
        }
    }

    /**
     * Without the model nothing waits for a vector, so the deepen counts read zero. Under the first run's SAMPLE scope,
     * pending files outside the plan are [Store.Counts.waiting], not pending, so "All caught up" holds for what was asked.
     */
    fun counts(): Flow<Store.Counts> = combine(store.counts(), modelMissing, sampleIds) { c, missing, ids ->
        val counts = if (textOnly || missing) c.copy(tailChunks = 0, tailFiles = 0) else c
        if (ids == null) return@combine counts
        val pending = store.pendingIn(ids)
        val perKind = pending.groupingBy { it.file.kind }.eachCount()
        val asked = pending.size.toLong()
        counts.copy(
            byState = c.byState + (FileState.PENDING to asked),
            byKindState = c.byKindState.filterKeys { it.second != FileState.PENDING } +
                Kind.entries.associate { (it to FileState.PENDING) to (perKind[it]?.toLong() ?: 0L) },
            waiting = ((c.byState[FileState.PENDING] ?: 0L) - asked).coerceAtLeast(0),
            trial = true,
        )
    }.flowOn(dispatchers.default)

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
        batchSoon = false
        try {
            return coroutineScope {
                val work = async(dispatchers.indexing) { runBatch(budget, deepen) }
                batchJob = work
                try {
                    work.await()
                } catch (e: CancellationException) {
                    ensureActive() // the caller was cancelled, not just this batch
                    _state.update { it.copy(running = false, current = null, currentFile = null, lastStop = StopReason.BUDGET) }
                    NextRun.SOON
                }
            }.also { batchSoon = it == NextRun.SOON }
        } finally {
            batchJob = null
            indexLock.unlock()
            releaseIfIdle()
        }
    }

    private suspend fun runBatch(budget: Duration, mayDeepen: Boolean): NextRun {
        // The budget covers the sync and the model's open too, so a platform's time limit holds.
        val started = TimeSource.Monotonic.markNow()
        changedSinceSync = false
        // The ETA and pace carry over: a batch boundary is not new information.
        _state.update {
            IndexState(
                running = true,
                lastStop = it.lastStop,
                filesPerMinute = it.filesPerMinute,
                etaMinutes = it.etaMinutes,
                build = it.build,
                backend = it.backend,
                detail = it.detail,
                recentFiles = it.recentFiles,
            )
        }
        var stop: StopReason? = null
        var error: String? = null
        var used: Embedder? = null
        var build: ModelBuild? = null
        fun end(reason: StopReason): NextRun {
            stop = reason
            return nextRunFor(reason, changedSinceSync)
        }
        try {
            // Battery Saver waits for a power-state change, without walking folders first.
            if (readingMonitor.state().powerSave) return end(StopReason.POWER_SAVE)
            // Stars follow their files: hidden while a file is gone, back when it returns.
            sync.sync().let {
                if (it.removed > 0 || it.added > 0) _starred.value = store.starredUris()
                if (it.removed > 0) reconcileRecentFiles()
            }
            // Not tuned yet: the sync kept names current, and files wait to be read in full.
            val stored = if (textOnly) textOnlyProfile else store.profile() ?: return end(StopReason.DONE)
            // Read per file, so a switch flipped mid-batch applies at once.
            val settings = {
                val p = batchPrefs()
                IndexSettings(p.effective(if (textOnly) textOnlyProfile else store.profile() ?: stored), p.kinds, p.paused)
            }
            val busy = { monitor.state().screenOn && !uiVisible }
            // A visible app reads on battery too; hidden, a low battery waits for the charger like the setting does.
            // An unknown level (a negative value) is not low.
            val requireCharging = { s: DeviceState -> !uiVisible && (batchPrefs().effective(stored).bulkOnlyWhenCharging || s.batteryPct in 0..LOW_BATTERY) }
            val device = readingMonitor.state()
            // The loop's own checks, before the model's seconds-long open and its memory.
            IndexEngine.stopReason(settings(), device, busy(), requireCharging(device))?.let { return end(it) }
            // Under the first run's SAMPLE scope only the plan is work: the idle check, the ETA and the engine's pages.
            selectPace(factory.profileNow(stored, uiVisible))
            trimTaste()
            val ids = loadScope()
            fun pendingNow(): Pair<Map<Kind, Long>, Long> {
                val currentIds = sampleIds.value
                return if (currentIds == null) {
                    store.pendingByKind() to store.pendingBytes(Kind.VIDEO)
                } else {
                    store.pendingIn(currentIds).byKind().let { (k, bytes) -> k.mapValues { it.value.toLong() } to bytes }
                }
            }
            val pending = pendingNow().first
            // An open app deepens once nothing is pending; in the background only on the charger with the screen off.
            val deepen = mayDeepen && !textOnly && (uiVisible || (device.charging && !device.screenOn))
            // Nothing to read, no tail to embed, and vectors of the current model: opening it would do nothing.
            val idle = pending.values.sum() == 0L &&
                (!deepen || (if (ids == null) store.tailChunkCount() else store.tailChunksIn(ids).size.toLong()) == 0L) &&
                (textOnly || store.signature() == Signature.of(requiredBuild.value, stored.visionTokens, stored.maxTokens))
            if (idle) return end(StopReason.DONE)
            val e = if (textOnly) {
                null
            } else {
                // A missing model file returns here, like the untuned case above.
                val (opened, b) = model.open() ?: return end(StopReason.DONE)
                used = opened
                build = b
                if (store.signature() != opened.signature) {
                    log("Model signature changed from ${store.signature()?.value} to ${opened.signature.value}; indexing everything again", null)
                    store.resetForModel(opened.signature)
                }
                opened
            }
            val openedProfile = factory.profileNow(stored, uiVisible).copy(
                backend = e?.backend ?: Backend.CPU,
                visionBackend = e?.visionBackend ?: Backend.CPU,
            )
            selectPace(openedProfile)
            trimTaste()
            val configuration = model.currentConfiguration
            val changedConfiguration = {
                val current = factory.profileNow(store.profile() ?: stored, uiVisible)
                !textOnly && configuration != (current.backend to current.visionBackend)
            }
            val (left, videoBytes) = pendingNow()
            eta.start(left, videoBytes)
            _state.update { it.copy(pendingAtStart = left.values.sum(), etaMinutes = eta.minutes, filesPerMinute = eta.filesPerMinute) }
            val engine = if (ids == null) {
                IndexEngine(
                    store, extractor, e, readingMonitor, settings, ::onFile, busy, ::awaitUi,
                    onCurrent = ::onCurrent, configurationChanged = changedConfiguration,
                )
            } else {
                val rank = ids.withIndex().associate { it.value to it.index }
                // Tails of planned files deepen after their heads; an empty plan stops DONE and nothing else is read.
                IndexEngine(
                    store, extractor, e, readingMonitor, settings, ::onFile, busy, ::awaitUi,
                    // One at a time: a measured pace can trim the remaining plan after any success.
                    pendingPage = { store.pendingIn(sampleIds.value.orEmpty()).sortedBy { rank[it.id] }.take(1) },
                    tailPage = { store.tailChunksIn(sampleIds.value.orEmpty()) },
                    onCurrent = ::onCurrent,
                    configurationChanged = changedConfiguration,
                )
            }
            val result = engine.runBatch(budget - started.elapsedNow(), requireCharging, deepen = deepen)
            return end(result.stopReason)
        } catch (e: CancellationException) {
            throw e
        } catch (e: EmbedderClosedException) {
            log("The model closed mid-batch", e)
            val broke = model.broke(used, build, e)
            // An open refused for now (too little memory) rests, or the platform loops straight into another refusal.
            // A failed NPU build waits for the generic download; anything else reopens and carries on.
            return when {
                used == null && !e.broken -> NextRun.AFTER_COOLDOWN
                broke && build?.isNpu == true -> NextRun.DONE
                else -> NextRun.SOON
            }
        } catch (e: Exception) {
            log("Indexing failed", e)
            error = "Something went wrong reading your files. Retriever will try again."
            return NextRun.RETRY
        } finally {
            saveBook()
            _state.update { it.copy(running = false, current = null, currentFile = null, lastStop = stop, lastError = error) }
        }
    }

    /** Text-only mode reads screenshots, PDFs and documents; photos, video and audio stay findable by name. */
    private fun batchPrefs(): Prefs = store.prefs().let { if (textOnly) it.copy(kinds = it.kinds intersect textOnlyKinds) else it }

    private val readingMonitor = object : DeviceMonitor {
        override fun state(): DeviceState = monitor.state().let { if (uiVisible) it.copy(powerSave = false) else it }
    }

    private fun reconcileRecentFiles() {
        _state.update { state -> state.copy(recentFiles = state.recentFiles.filter { store.fileByUri(it.uri) != null }) }
    }

    private fun onCurrent(file: SourceFile?) {
        _state.update { it.copy(current = file?.name, currentFile = file) }
    }

    private fun onFile(m: FileMetric) {
        retierFrom(m)
        if (!m.ok) {
            log("Couldn't index ${m.file.kind} (${m.file.mime}, ${m.file.size} B): ${m.cause?.let { it::class.simpleName }}", m.cause)
        } else {
            log("Indexed ${m.file.kind}: extract ${m.extractMs.toInt()} ms, embed ${m.embedMs.toInt()} ms, ${m.pieces} pieces", null)
        }
        eta.add(m.file.kind, m.extractMs + m.embedMs, SpeedBook.units(m))
        if (now() - bookSavedAt >= SAVE_EVERY_MS) saveBook()
        _state.update {
            it.copy(
                processed = it.processed + if (m.ok) 1 else 0,
                failed = it.failed + if (m.ok) 0 else 1,
                recentFiles = if (m.ok && store.fileByUri(m.file.uri)?.state == FileState.INDEXED) {
                    (listOf(m.file) + it.recentFiles.filterNot { f -> f.uri == m.file.uri }).take(RECENT_FILES)
                } else {
                    it.recentFiles
                },
                filesPerMinute = eta.filesPerMinute,
                etaMinutes = eta.minutes,
            )
        }
        if (m.ok) trimTaste()
    }

    private fun saveBook() {
        val book = eta.book
        bookSavedAt = now()
        if (book == savedBook) return
        store.setMeta(speedKey, book.encode())
        savedBook = book
    }

    private fun paceKey(profile: DeviceProfile): String = if (profile.backend == Backend.CPU) {
        SPEED_KEY
    } else {
        "speed_by_kind_${profile.backend}_${profile.visionBackend ?: profile.backend}"
    }

    private fun bookFor(profile: DeviceProfile): SpeedBook {
        val key = paceKey(profile)
        val book = if (key == speedKey) eta.book else SpeedBook.decode(store.meta(key))
        val seed = if ((profile.visionBackend ?: profile.backend) == Backend.CPU) profile.cpuImageMs else profile.gpuImageMs
        return book.copy(photoSeedMs = seed ?: book.photoSeedMs)
    }

    private fun selectPace(profile: DeviceProfile) {
        val key = paceKey(profile)
        val book = bookFor(profile)
        if (key == speedKey) {
            eta.book = book
            return
        }
        saveBook()
        speedKey = key
        eta.changeBook(book)
        savedBook = null
        photoMs.clear()
    }

    /**
     * The whole remaining read at this phone's measured pace; null before any file is measured. No hours from it while
     * [ESTIMATE_CALIBRATED] is false.
     */
    @Throws(Throwable::class)
    suspend fun estimateAll(): ReadEstimate? = io {
        val ios = probe.platform == Platform.IOS
        val book = if (_state.value.running) eta.book else store.profile()?.let { bookFor(factory.profileNow(it, uiVisible)) } ?: eta.book
        readEstimate(book, store.pendingByKind(), store.pendingBytes(Kind.VIDEO), ios, if (ios) BgSpeed.decode(store.meta(BG_SPEED_KEY)) else null)
    }

    /** iOS: one background run read [files] in [seconds]; after the first, [estimateAll] counts nights. */
    @Throws(Throwable::class)
    suspend fun recordBackgroundRun(files: Int, seconds: Long) = io {
        store.setMeta(BG_SPEED_KEY, (BgSpeed.decode(store.meta(BG_SPEED_KEY)) ?: BgSpeed()).add(files, seconds).encode())
    }

    private suspend fun awaitUi() {
        currentCoroutineContext().ensureActive()
        combine(uiBusy, searching) { busy, n -> !busy && n == 0 }.first { it }
    }

    // --- First run ---

    /** [planTaste] at its 90-second budget; Swift sees no default arguments. */
    @Throws(Throwable::class)
    suspend fun planTaste(): TastePlan = planTaste(TASTE_BUDGET)

    /**
     * Syncs the names, then plans a varied slice of the library that reads in about [budget] at this phone's pace (the
     * tune's photo time and measured paces, else seeds) and sets the scope to SAMPLE. Writes Store meta, never Prefs, so
     * the first tune still applies its tier defaults. Text-only phones plan only the kinds they read inside.
     */
    @Throws(Throwable::class)
    suspend fun planTaste(budget: Duration): TastePlan = indexLock.withLock {
        io {
            sync.sync().let {
                if (it.removed > 0 || it.added > 0) _starred.value = store.starredUris()
                if (it.removed > 0) reconcileRecentFiles()
            }
            val kinds = store.prefs().kinds intersect readableKinds
            val files = store.knownFiles().values.filter { it.kind in kinds }
            store.profile()?.let { selectPace(factory.profileNow(it, uiVisible)) }
            val plan = FileRules.planTaste(files, eta.book::costMs, budget)
            store.setMeta(TASTE_BUDGET_KEY, budget.inWholeMilliseconds.toString())
            val byUri = store.filesByUris(plan.ids)
            store.setTaste(plan.ids.mapNotNull { byUri[it]?.id })
            loadScope()
            plan
        }
    }

    /** The short read: one batch of the planned files, which ends DONE once they and their tails are read. */
    @Throws(Throwable::class)
    suspend fun taste(): NextRun = indexBatch(TASTE_BATCH, waitForLock = true)

    @Throws(Throwable::class)
    suspend fun hasPendingTaste(): Boolean = io { store.sampleIds()?.let { store.pendingIn(it).isNotEmpty() } == true }

    /** Under indexLock: keep completed work, drop videos first then the most expensive pending files. */
    internal fun trimTaste() {
        val ids = store.sampleIds() ?: return
        val pending = store.pendingIn(ids)
        val budget = store.meta(TASTE_BUDGET_KEY)?.toDoubleOrNull() ?: TASTE_BUDGET.inWholeMilliseconds.toDouble()
        val costs = pending.associate { it.id to eta.book.costMs(it.file) }
        var left = costs.values.sum()
        if (left <= budget * 1.5) return
        // An over-budget file is still a trial; never trim away its last chance to read anything.
        val minimum = if (store.indexedIn(ids).isEmpty()) 1 else 0
        val drop = mutableSetOf<Long>()
        for (file in pending.sortedWith(compareByDescending<Store.StoredFile> { it.file.kind == Kind.VIDEO }.thenByDescending { costs.getValue(it.id) })) {
            if (left <= budget || pending.size - drop.size <= minimum) break
            drop += file.id
            left -= costs.getValue(file.id)
        }
        store.setTaste(ids.filterNot { it in drop })
        loadScope()
        val (counts, bytes) = pending.filterNot { it.id in drop }.byKind()
        eta.start(counts.mapValues { it.value.toLong() }, bytes)
    }

    /** How far the short read is; re-emits as files are read. */
    fun tasteProgress(): Flow<TasteProgress> = combine(store.counts(), sampleIds) { _, _ ->
        val ids = store.tasteIds()
        val planned = ids.chunked(500).flatMap { store.files(it).values }.groupingBy { it.kind }.eachCount()
        val (pending, videoBytes) = store.pendingIn(ids).byKind()
        TasteProgress.of(planned, pending, videoBytes, eta.book)
    }.distinctUntilChanged().flowOn(dispatchers.default)

    /** Read everything: scope ALL and the charging choice. The caller schedules the reading. */
    @Throws(Throwable::class)
    suspend fun commit(whileCharging: Boolean) {
        updatePrefs { it.copy(onlyWhileCharging = whileCharging) }
        io {
            store.inTransaction {
                store.setMeta(TASTE_SCOPE_KEY, Scope.ALL.name)
                store.setFlag(TASTE_DECIDED_KEY)
            }
            loadScope()
        }
        // A short read still running ends DONE; this makes it SOON, so the next batch reads the rest.
        changedSinceSync = true
    }

    /** "Not yet": decided, and the scope stays SAMPLE. What was read stays searchable; Home offers the choice again. */
    @Throws(Throwable::class)
    suspend fun decideLater() = io { store.setFlag(TASTE_DECIDED_KEY) }

    /** True once the person chose (commit or Not yet) or never had a short read: the try-it and commit screens are done. */
    @Throws(Throwable::class)
    suspend fun tasteDecided(): Boolean = io { store.tasteScope() == Scope.ALL || store.flag(TASTE_DECIDED_KEY) }

    /** Whether the first full read was celebrated; for the CAUGHT_UP hook. */
    @Throws(Throwable::class)
    suspend fun celebrated(): Boolean = io { store.flag(CELEBRATED_KEY) }

    @Throws(Throwable::class)
    suspend fun markCelebrated() = io { store.setFlag(CELEBRATED_KEY) }

    // --- Model ---

    /** This phone's NPU build failed, so it runs the generic one. Reads the store; call off the main thread. */
    fun fellBack(): Boolean = model.fellBack()

    /** Frees the model's memory; the next search or batch opens it again. */
    @Throws(Throwable::class)
    suspend fun release() {
        model.release()
    }

    /** Hidden work gives memory back when its last user finishes; an imminent follow-up keeps the model warm. */
    @Throws(Throwable::class)
    suspend fun releaseIfIdle() {
        if (uiVisible) return
        releaseWanted = true
        retryRelease()
    }

    /**
     * Finishes a release that was asked for. Searches and warm-ups call only this, never [releaseIfIdle]: a hidden search
     * (a Shortcut) that closed the model it had just warmed would leave every later hidden search on keywords alone.
     */
    private suspend fun retryRelease() = withContext(NonCancellable) {
        if (!releaseWanted || uiVisible || searching.value != 0) return@withContext
        if (!indexLock.tryLock()) return@withContext
        try {
            if (model.releaseIf { !uiVisible && (!batchSoon || prefs.value.paused) && searching.value == 0 }) releaseWanted = false
        } finally {
            indexLock.unlock()
        }
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
        scope: List<Long>? = null,
    ): SearchEngine.SearchResult = searchWith { s -> parsedSearch(q, kinds, limit, onlyStarred, literal, relax = true, scope) { s.search(it) } }

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
        scope: List<Long>? = null,
    ): SearchEngine.SearchResult = io { parsedSearch(q, kinds, limit, onlyStarred, literal, relax = false, scope) { newEngine(NoModel).names(it) } }

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
        scope: List<Long>?,
        run: suspend (SearchRequest) -> SearchEngine.SearchResult,
    ): SearchEngine.SearchResult {
        val p = if (literal) ParsedQuery(q.trim()) else QueryParser.parse(q, now(), zoneOffsetMs())
        val ids = fileIdsFor(onlyStarred, scope)
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

    private fun starredIds(onlyStarred: Boolean): Set<Long>? = if (onlyStarred) store.starredFiles(Int.MAX_VALUE).mapTo(HashSet()) { it.id } else null

    /** The files a search may return: the starred ones and/or the first run's planned [scope]; null, any. */
    private fun fileIdsFor(onlyStarred: Boolean, scope: List<Long>?): Set<Long>? {
        val starred = starredIds(onlyStarred)
        return when {
            scope == null -> starred
            starred == null -> scope.toSet()
            else -> scope.filterTo(HashSet()) { it in starred }
        }
    }

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
    suspend fun searchImage(
        jpeg: ByteArray,
        kinds: Set<Kind>? = null,
        limit: Int = 20,
        onlyStarred: Boolean = false,
        scope: List<Long>? = null,
    ): SearchEngine.SearchResult {
        val ids = io { fileIdsFor(onlyStarred, scope) }
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
                    model.broke(used, build, e)
                    run(newEngine(NoModel))
                }
            }
        } finally {
            searching.update { it - 1 }
            retryRelease()
        }
    }

    /**
     * The search engine with the embedder it uses and that embedder's build (both null: keyword-only). Never opens the
     * model on the search's path: with none open it answers from keywords now and opens it off the path for the next.
     */
    private fun engine(): Triple<SearchEngine, Embedder?, ModelBuild?> {
        // The model is opening (seconds) or Tune is measuring: answer from keywords now instead of waiting. A model file that
        // went away under an open embedder stops answering by meaning too, as a reopen would.
        if (_modelMissing.value || !model.lock.tryLock()) return Triple(newEngine(NoModel), null, null)
        val found = try {
            Triple(engine ?: newEngine(model.current ?: NoModel).also { engine = it }, model.current, model.currentBuild)
        } finally {
            model.lock.unlock()
        }
        // ponytail: two searches at once can both start an open; the second waits on the lock and finds it open.
        if (found.second == null && !noModel() && opening?.isActive != true) opening = background.launch { openAhead() }
        return found
    }

    /** Opens the model ahead of a search, once set up; a failure is logged and the search stays on keywords. */
    private suspend fun openAhead() {
        if (_setUp.value != true || textOnly) return
        try {
            // A search warm-up must not replace the handle held by a running file or batch.
            indexLock.withLock { model.open() }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Exception) {
            log("Couldn't open the model ahead of a search", t)
        } finally {
            retryRelease()
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
        val s = model.lock.withLock { engine ?: newEngine(model.current ?: NoModel).also { engine = it } }
        coroutineScope {
            launch { s.refresh() }
            openAhead()
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
        // Reads every file's chunks, and only the caught-up line shows it: counted once nothing waits and the counts
        // moved since the last count, else the last count.
        val byState = store.countsByState()
        val cached = nameOnlyAt
        val nameOnly = if ((byState[FileState.PENDING] ?: 0L) == 0L && cached?.first != byState) {
            store.nameOnlyCount().also { nameOnlyAt = byState to it }
        } else {
            cached?.second ?: 0L
        }
        HomeFacts(
            newMedia = store.newestFiles(setOf(Kind.PHOTO, Kind.VIDEO), 15).map { it.file },
            newShots = store.newestFiles(setOf(Kind.SCREENSHOT), 10).map { it.file },
            newDocs = store.newestFiles(setOf(Kind.PDF, Kind.DOC), 8).map { it.file },
            starred = store.starredVisible(10).map { it.file },
            starredCount = store.starredVisibleCount(),
            folders = store.folderCount(),
            largeCount = largeCount,
            largeBytes = largeBytes,
            nameOnly = nameOnly,
        )
    }

    /**
     * Past years' photos and videos taken on today's date. [days]: (year, local midnight ms, next local midnight ms - 1),
     * newest year first, built by the platform with real zone rules so core needs no date library. Stops at five years
     * with something, and skips years older than the oldest photo.
     */
    @Throws(Throwable::class)
    suspend fun onThisDay(days: List<Triple<Int, Long, Long>>): List<YearMemory> = io {
        val earliest = store.mediaTakenSince() ?: return@io emptyList()
        val hits = ArrayList<Pair<Int, Store.MediaSpan>>(5)
        for ((year, from, to) in days) {
            if (hits.size == 5) break
            if (to < earliest) continue
            val span = store.mediaTakenBetween(from, to)
            if (span.count > 0 && span.coverId != null) hits += year to span
        }
        val covers = store.files(hits.map { it.second.coverId!! })
        hits.mapNotNull { (year, s) -> covers[s.coverId]?.let { YearMemory(year, s.count - s.videos, s.videos, it) } }
    }

    /** A file's text in reading order: what the in-app reader shows. Empty for files read by name only. */
    @Throws(Throwable::class)
    suspend fun fileText(fileId: Long): List<ChunkText> = io {
        store.chunkTexts(fileId).mapNotNull { c -> c.text?.let { ChunkText(c.ordinal, c.locator, it) } }
    }

    @Throws(Throwable::class)
    suspend fun fileCount(): Long = io { store.fileCount() }

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

    /** The photos and videos taken from [from] to [to] (ms, both ends in), oldest first: what a year card of On this day opens. */
    @Throws(Throwable::class)
    suspend fun filesTakenBetween(from: Long, to: Long, limit: Int, offset: Int): List<Store.StoredFile> =
        io { store.filesTakenBetween(from, to, limit, offset) }

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
            model.lock.withLock {
                io {
                    model.closeLocked()
                    calibrateLocked(samples, tuneAgain)
                }
            }
        }
    }

    private suspend fun calibrateLocked(samples: Samples, tuneAgain: Boolean): CalibrationResult {
        reload()
        if (tuneAgain) model.clearGpuUnsafe()
        model.checkUnfinishedOpens()
        val build = requiredBuild.value
        val stored = store.profile()
        val result = try {
            model.countedOpen { Calibration(model.measurable(), frames = frames, visible = { uiVisible }).run(samples, build, probe.ramGb) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (build.isNpu) model.markNpuFailed(build, e)
            throw e
        }
        log(
            "Tune: " + result.backends.joinToString("; ") {
                "${it.backend} image ${it.imageMs?.toInt()} ms, query ${it.queryMs?.toInt()} ms, frame gap ${it.maxFrameGapMs.toInt()} ms" +
                    if (it.ok) "" else " (failed)"
            } + "; chose ${result.chosen}",
            null,
        )
        // Measured at the reference vision tokens; Tune again keeps the stored ones, or every vector would be stale.
        val profile = if (tuneAgain && stored != null) result.profile.copy(visionTokens = stored.visionTokens) else result.profile
        applyProfile(stored, profile)
        selectPace(factory.profileNow(profile, uiVisible))
        val vision = factory.profileNow(profile, uiVisible).visionBackend
        eta.book = eta.book.copy(photoSeedMs = if (vision == Backend.CPU) profile.cpuImageMs else profile.gpuImageMs)
        saveBook()
        trimTaste()
        return result.copy(profile = profile)
    }

    /** Stores [profile]; settings still at [stored]'s defaults take its defaults, and files read under the old ones are read again. */
    private fun applyProfile(stored: DeviceProfile?, profile: DeviceProfile) {
        store.setProfile(profile)
        if (!store.hasPrefs()) {
            store.updatePrefs { Prefs.defaultFor(profile) }
        } else if (stored == null) {
            // First tune after an earlier write (mobile data, the welcome tap): each field still untouched takes the tier default.
            store.updatePrefs { it.retuned(null, profile) }
        } else if (stored.tier != profile.tier) {
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
        applyProfile(
            stored,
            stored.copy(tier = seen.tier, videoFrameEverySec = seen.videoFrameEverySec, audio = seen.audio, bulkOnlyWhenCharging = seen.bulkOnlyWhenCharging),
        )
    }

    internal companion object {
        const val RECENT_FILES = 24
        const val LARGE_FILE = 100L * 1024 * 1024

        /** Battery percent at or below which a hidden app waits for the charger. */
        const val LOW_BATTERY = 20

        const val SPEED_KEY = "speed_by_kind_CPU_CPU"
        const val BG_SPEED_KEY = "bg_speed"
        const val SAVE_EVERY_MS = 30_000L

        /** The short read's plan, and the hard cap of its batch (onboarding §3.2). */
        val TASTE_BUDGET = 90.seconds
        val TASTE_BATCH = 120.seconds

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
            StopReason.POWER_SAVE -> NextRun.DONE
            StopReason.UNPLUGGED -> NextRun.WHEN_CHARGING
            StopReason.BUSY -> NextRun.WHEN_IDLE
            StopReason.DONE -> if (changedSinceSync) NextRun.SOON else NextRun.DONE
            StopReason.PAUSED -> NextRun.DONE
        }
    }
}
