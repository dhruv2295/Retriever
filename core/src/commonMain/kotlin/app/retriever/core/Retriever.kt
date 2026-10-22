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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    /**
     * The file in extraction/embedding now, kept between two files of a batch and cleared whenever reading stops: null
     * while running is only the batch's start, before its first file ("Getting started…").
     */
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
)

/**
 * Home's facts besides the counts (read by name only is [ReadingProgress.nameOnly]): the newest pictures, screenshots
 * and documents, starred files, folders, large files, and More on your phone by kind ([kindTotals]: every file Retriever
 * can access, OTHER included, kinds with none absent) and by folder ([folderTotals]: each folder alone, most files
 * first). Never OTHER files in the lists: app databases and dumps are not something to show.
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
    /** Files that share a size with another, so could be copies; Home's Find duplicate files shows only when some do. */
    val possibleCopies: Long = 0,
    val kindTotals: Map<Kind, FileTotals> = emptyMap(),
    val folderTotals: List<FolderTotals> = emptyList(),
) {
    /** Home's "Recently added": the three newest lists as one, newest first (taken, else modified), no new query. */
    val recentlyAdded: List<SourceFile> = (newMedia + newShots + newDocs).sortedByDescending { it.takenAt ?: it.modifiedAt }

    /**
     * Home's Browse, in its fixed order: each kind's name and count, no sizes (those are Settings → Storage); PDFs count
     * under Documents, and a kind with no files is left out. Folders is the app's own last tile ([folders]).
     */
    val browse: List<BrowseKind> = listOf(listOf(Kind.PHOTO), listOf(Kind.VIDEO), listOf(Kind.SCREENSHOT), listOf(Kind.DOC, Kind.PDF), listOf(Kind.AUDIO))
        .mapNotNull { kinds ->
            kinds.sumOf { kindTotals[it]?.files ?: 0L }.takeIf { it > 0 }?.let { BrowseKind(Words.kindLabelPlural(kinds.first()), kinds, it) }
        }
}

/** One Browse tile on Home: its name, the kinds it opens, and how many files. */
data class BrowseKind(val title: String, val kinds: List<Kind>, val files: Long)

/** A group of files: how many, the sum of the sizes known, and how many have no known size (iOS Photos). */
data class FileTotals(val files: Long, val knownBytes: Long, val unknownSizes: Long)

data class FolderTotals(val path: String, val totals: FileTotals)

/** The photos and videos taken on one calendar day in [year], with a photo for the cover when there is one. */
data class YearMemory(val year: Int, val photos: Long, val videos: Long, val cover: SourceFile)

/** One piece of a file's text, in reading order, for the in-app reader. */
data class ChunkText(val ordinal: Int, val locator: String, val text: String)

/** What a phone with too little memory for the model reads inside: the words in screenshots, PDFs and documents. */
val textOnlyKinds: Set<Kind> = setOf(Kind.PHOTO, Kind.SCREENSHOT, Kind.PDF, Kind.DOC)

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
    /** The device's own names in shared sentences; iOS passes its words. */
    private val words: DeviceWords = DeviceWords.ANDROID,
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

    private val _modelMissing = MutableStateFlow(!textOnly)
    private val _modelPresent = MutableStateFlow(false)

    /** Downloaded bytes are present; tuning/opening is a separate concern. Refreshed by refreshSetUp and each batch. */
    val modelPresent: StateFlow<Boolean> = _modelPresent.asStateFlow()
    val modelDownloadBytes: Long get() = requiredBuild.value.sizeBytes
    val audioContentSupported: Boolean get() = false

    /** Set up, but the model file is gone (freed, or a fallback build not downloaded yet): names and words search only. */
    val modelMissing: StateFlow<Boolean> = _modelMissing.asStateFlow()

    private val _starred = MutableStateFlow<Set<String>>(emptySet())

    /** Starred URIs, so a row's star is one lookup. */
    val starred: StateFlow<Set<String>> = _starred.asStateFlow()

    /** The first run's planned ids while only they are read (scope SAMPLE); null reads everything. Mirrors Store meta. */
    private val sampleIds = MutableStateFlow<List<Long>?>(null)

    private fun loadScope(): List<Long>? = exclusive {
        store.sampleIds().also {
            sampleIds.value = it
            _setup.value = readSetup()
        }
    }

    /** The planned ids while only they are read, for a search scoped to them ([search]'s `scope`); null once everything is read. */
    val scopeIds: StateFlow<List<Long>?> = sampleIds.asStateFlow()

    private val _setup = MutableStateFlow<SetupInputs?>(null)

    /** Setup's persisted inputs, null until [refreshSetUp] loads them; the platform adds [SetupInputs.withAccess]. */
    val setup: StateFlow<SetupInputs?> = _setup.asStateFlow()

    /**
     * Reads and publishes as one step: the launch's reload and an answer run at once, and a read taken before the answer
     * was written must not land after it (it sent iOS back to the welcome, which then marked access chosen over CHOOSING).
     */
    private fun loadSetup(): SetupInputs = exclusive { readSetup().also { _setup.value = it } }

    private fun readSetup(): SetupInputs {
        val stored = store.meta(SETUP_MARK_KEY)
        val saved = SetupMark.saved(stored)
        // Set up before this setup existed: past access and done with setup, a sample still running included. A text-only
        // phone never tunes, and from before setupStarted it shows only as saved prefs.
        val legacy = stored == null && (store.prefs().setupStarted || store.profile() != null || (textOnly && store.hasPrefs()))
        val scope = store.meta(TASTE_SCOPE_KEY)
        return SetupInputs(
            mark = if (legacy) SetupMark.ACCESS_CHOSEN else saved ?: SetupMark.NONE,
            skipped = legacy || store.flag(SETUP_SKIPPED_KEY),
            // No scope yet reads as ALL elsewhere; here it is a plan still to make, unless setup predates plans.
            decided = store.flag(TASTE_DECIDED_KEY) || scope == Scope.ALL.name || (scope == null && legacy),
            planned = store.tasteIds().size,
        )
    }

    /**
     * The person's answer on a setup screen: Start the demo, its Continue, or Back. CHOOSING stops the demo where it is (its
     * unfinished files join the rest); NONE, Back to the welcome, clears a skip.
     */
    @Throws(Throwable::class)
    suspend fun setSetupMark(mark: SetupMark): SetupInputs {
        val setup = io {
            store.inTransaction {
                store.setMeta(SETUP_MARK_KEY, mark.name)
                if (mark == SetupMark.NONE) store.deleteMeta(SETUP_SKIPPED_KEY)
            }
            loadSetup()
        }
        if (mark == SetupMark.CHOOSING) cancelBatch()
        return setup
    }

    /** Skip the demo: setup goes to the choice, with no demo line. */
    @Throws(Throwable::class)
    suspend fun skipFirstRun() = io {
        store.setFlag(SETUP_SKIPPED_KEY)
        loadSetup()
    }

    /** Showing drops a pending model release; reading still honors the saved schedule. */
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
    private val eta by lazy { Eta(SpeedBook.decode(store.meta(SPEED_KEY)), now).also { savedBook = it.book } }
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
        _requiredBuild.value = ModelBuilds.installedOrSelected(probe, store.profile(), store.signature(), model.npuFailed(), modelReady)
        // Tune writes the profile with the model present, so it marks a finished setup; losing the file later keeps it.
        val tuned = store.profile() != null
        _modelPresent.value = modelReady(_requiredBuild.value)
        _modelMissing.value = !textOnly && !_modelPresent.value
        _setUp.value = platformReady() && (textOnly || tuned)
    }

    /** Reading adds no meaning until the tune: an untuned open could pick other vision tokens and redo every vector. */
    private fun noModel() = textOnly || _setUp.value != true || !modelReady(requiredBuild.value)

    fun setUiBusy(busy: Boolean) {
        uiBusy.value = busy
    }

    /** Files changed (a MediaStore or PhotoKit notification): the running batch syncs again when it ends. */
    fun notifyChanged() {
        changedSinceSync = true
    }

    /** No included source is permitted. Keep preferences and stars, but drop inaccessible file revisions. */
    @Throws(Throwable::class)
    suspend fun clearUnavailableLibrary() {
        cancelBatch()
        indexLock.withLock {
            io {
                sync.remove(store.knownUris().toList())
                reconcileRecentFiles()
                _starred.value = store.starredUris()
                libraryChanged()
            }
        }
    }

    /** Stops the running batch within one file; that [indexBatch] returns [NextRun.SOON]. */
    fun cancelBatch() {
        batchJob?.cancel()
    }

    @Throws(Throwable::class)
    suspend fun pause() {
        updatePrefs { endSession(it).copy(paused = true) }
        if (_state.value.currentFile != null) cancelBatch()
    }

    @Throws(Throwable::class)
    suspend fun resume() = updatePrefs { it.copy(paused = false) }

    /** Ends a Read now session without pausing: reading goes back to the saved schedule (a system expiry, not the person's Pause). */
    @Throws(Throwable::class)
    suspend fun endReadNowSession() = updatePrefs { endSession(it) }

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
                libraryChanged()
            }
            if (new.paused) releaseIfIdle()
        }
    }

    /**
     * [read] of the latest [changes], once per burst of them (conflated) and once for all collectors (shared), only while
     * something collects: a screen that is gone costs nothing, and ten screens cost one. A new collector first gets a
     * fresh [read] of [current], never a value from before its subscription. No failure reaches a collector (on iOS an
     * uncaught one ends the process): one in the shared read is logged and ends the sharing; a failed first read is
     * logged and skipped, and the collector waits for the next change.
     */
    private fun <K, T> watched(changes: Flow<K>, current: () -> K, read: (K) -> T): Flow<T> {
        val shared = changes.conflate().map { read(it) }
            .catch { log("A watched read failed", it) }
            .shareIn(background, SharingStarted.WhileSubscribed())
        return shared.onSubscription {
            runCatching { read(current()) }.onSuccess { emit(it) }.onFailure { log("A watched read failed", it) }
        }.distinctUntilChanged().flowOn(dispatchers.default)
    }

    /** Meaning work waits for the model and its tune, as reading does. */
    private fun countsKey(missing: Boolean, setUp: Boolean?, ids: List<Long>?, p: Prefs) =
        Triple(ids, !textOnly && !missing && setUp == true, p.kinds intersect readableKinds)

    private fun countsOf(key: Triple<List<Long>?, Boolean, Set<Kind>>) = store.countsNow(key.first, includeTails = key.second, kinds = key.third)

    private fun countsKeyNow() = countsKey(modelMissing.value, setUp.value, sampleIds.value, prefs.value)

    private val sharedCounts: Flow<Store.Counts> by lazy {
        // [library] too: a full scan's end (ReadingProgress.counted) is a meta write, which countChanges never sees.
        val changes = merge(store.countChanges(), library.drop(1).map { })
        watched(combine(changes, modelMissing, setUp, sampleIds, prefs) { _, m, s, ids, p -> countsKey(m, s, ids, p) }, ::countsKeyNow, ::countsOf)
    }

    /** Counts always describe usable content, including saved text when the model is absent. Shared; see [watched]. */
    fun counts(): Flow<Store.Counts> = sharedCounts

    @Volatile
    private var lowBattery = false

    /** The demo runs in front: planned, and [SetupInputs.demoRunning]. */
    private fun sampleSession(): Boolean = store.sampleIds() != null && SetupInputs(
        mark = SetupMark.saved(store.meta(SETUP_MARK_KEY)) ?: SetupMark.NONE,
        skipped = store.flag(SETUP_SKIPPED_KEY),
        decided = store.flag(TASTE_DECIDED_KEY),
    ).demoRunning

    /**
     * Why content can't be read now, or null when it can. Called between chunks, by the subscribed UI, and by a platform deciding
     * whether a run is worth a foreground service; expiry and safety stops never renew a session.
     */
    fun readingBlocker(): StopReason? {
        val device = monitor.state()
        lowBattery = !device.charging && (
            device.batteryPct in 0..ReadingPolicy.LOW_BATTERY ||
                (lowBattery && device.batteryPct in 0..ReadingPolicy.BATTERY_RECOVERED)
            )
        var p = store.prefs()
        if (p.readNowUntil != 0L && (p.readNowUntil <= now() || p.paused || device.powerSave || device.hot)) {
            p = store.updatePrefs { if (it.readNowUntil == p.readNowUntil) endSession(it) else it }
            _prefs.value = p
        }
        return ReadingPolicy.blocker(p, device, uiVisible, sampleSession(), now(), lowBattery)
    }

    /** What the status line reads from the device and the clock once a second. */
    private data class Tick(val blocker: StopReason?, val readNowMinutes: Int, val pluggedIn: Boolean)

    private data class StatusKey(val counts: Store.Counts, val prefs: Prefs, val state: IndexState, val tick: Tick)

    private fun tick(): Tick {
        val blocker = readingBlocker()
        val left = _prefs.value.readNowUntil - now()
        return Tick(blocker, if (left > 0) ((left + 59_999) / 60_000).toInt() else 0, monitor.state().pluggedIn)
    }

    /** Each conflated count change is one decision, so the line's "N of M files processed" moves as files finish. */
    private val sharedStatus: Flow<ReadingStatus> by lazy {
        val ticks = flow {
            while (true) {
                emit(tick())
                delay(1_000)
            }
        }.flowOn(dispatchers.default).distinctUntilChanged()
        watched(
            combine(sharedCounts, prefs, state, ticks, ::StatusKey),
            { StatusKey(countsOf(countsKeyNow()), prefs.value, state.value, tick()) },
        ) { (counts, p, state) ->
            // Fresh, not the key's tick: that can be a second old, and a Resume or a Read for 15 minutes shows at once.
            val t = tick()
            ReadingPolicy.decide(
                counts.progress,
                if (p.paused) StopReason.PAUSED else t.blocker,
                state.running,
                state.currentFile != null,
                words,
                t.readNowMinutes,
                t.pluggedIn,
            )
        }
    }

    /** Shared over [counts], like it: its once-a-second blocker check runs once however many screens watch. */
    fun readingStatus(): Flow<ReadingStatus> = sharedStatus

    private fun startSession(prefs: Prefs): Prefs {
        store.setMeta(SESSION_START_KEY, store.lastChunkId().toString())
        store.setMeta(SESSION_READ_KEY, "0")
        return prefs.copy(paused = false, readNowUntil = now() + ReadingPolicy.SESSION_MS)
    }

    private fun endSession(prefs: Prefs): Prefs {
        if (prefs.readNowUntil != 0L) store.setMeta(SESSION_READ_KEY, store.sessionReadCount(prefs.kinds intersect readableKinds).toString())
        return prefs.copy(readNowUntil = 0)
    }

    @Throws(Throwable::class)
    suspend fun readNow() {
        updatePrefs { startSession(it) }
        commitScope()
    }

    @Throws(Throwable::class)
    suspend fun setReadingSchedule(schedule: ReadingSchedule) {
        updatePrefs { it.withSchedule(schedule) }
        if (schedule != ReadingSchedule.MANUAL) commitScope()
        changedSinceSync = true
    }

    /** Claim only when the platform can post; survives process death and worker handoffs. */
    @Throws(Throwable::class)
    suspend fun claimSampleNotification(): Boolean = io {
        store.inTransaction {
            if (!sampleSession() || store.flag("sample_notified") ||
                store.indexedIn(store.tasteIds()).isEmpty()
            ) {
                false
            } else {
                // Seen on screen counts as told: a later hidden batch must not announce a sample already tried.
                store.setFlag("sample_notified")
                !uiVisible
            }
        }
    }

    /** The files among [fileIds] that search can't reach in full; files read whole are left out. */
    @Throws(Throwable::class)
    suspend fun readExtents(fileIds: List<Long>): Map<Long, ReadExtent> = io {
        store.readExtents(fileIds).mapNotNull { (id, extent) -> visibleExtent(extent)?.let { id to it } }.toMap()
    }

    /**
     * [deepen] false reads pending files only, so a batch with none left ends DONE instead of embedding long files'
     * later parts. [waitForLock]: wait for a running batch (being cancelled) however long it takes. Otherwise a batch still
     * unwinding gets [LOCK_WAIT]: a run that replaced a cancelled one found its lock held, ended DONE, and nothing came after
     * (a Pixel 8 Pro read nothing until the 6-hour run). A batch still running after that keeps the work.
     */
    @Throws(Throwable::class)
    suspend fun indexBatch(budget: Duration, deepen: Boolean = true, waitForLock: Boolean = false, sessionOnly: Boolean = false): NextRun {
        if (waitForLock) {
            indexLock.lock()
        } else if (withTimeoutOrNull(LOCK_WAIT) { indexLock.lock() } == null) {
            changedSinceSync = true
            return NextRun.DONE
        }
        batchSoon = false
        try {
            return coroutineScope {
                // Lazy, so [batchJob] is set before the batch runs: a cancelBatch() from the batch's first files must land.
                val work = async(dispatchers.indexing, start = CoroutineStart.LAZY) {
                    val remaining = store.prefs().readNowUntil - now()
                    if (remaining <= 0) {
                        runBatch(budget, deepen, sessionOnly)
                    } else {
                        withTimeoutOrNull(minOf(remaining, budget.inWholeMilliseconds).coerceAtLeast(1)) { runBatch(budget, deepen, sessionOnly) }
                            ?: run {
                                val reason = readingBlocker() ?: StopReason.BUDGET
                                _state.update { it.copy(lastStop = reason) }
                                nextRunFor(reason, changedSinceSync)
                            }
                    }
                }
                batchJob = work
                try {
                    work.await()
                } catch (e: CancellationException) {
                    ensureActive() // the caller was cancelled, not just this batch
                    val reason = readingBlocker() ?: StopReason.BUDGET
                    _state.update { it.copy(running = false, current = null, currentFile = null, lastStop = reason) }
                    nextRunFor(reason, changedSinceSync)
                }
            }.also { batchSoon = it == NextRun.SOON }
        } finally {
            batchJob = null
            indexLock.unlock()
            releaseIfIdle()
        }
    }

    private suspend fun runBatch(budget: Duration, mayDeepen: Boolean, sessionOnly: Boolean): NextRun {
        // The budget covers the sync and the model's open too, so a platform's time limit holds.
        val started = TimeSource.Monotonic.markNow()
        val changed = changedSinceSync
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
        var watchdog: Job? = null
        val processedBefore = _state.value.processed
        fun end(reason: StopReason): NextRun {
            stop = reason
            return nextRunFor(reason, changedSinceSync)
        }
        try {
            // A sync still running (the plan's tail) covers this batch, which reads now instead of waiting for it; a change
            // it may have scanned past stays marked, so the next batch syncs.
            sync.syncUnlessRunning()?.let(::synced) ?: run { if (changed) changedSinceSync = true }
            reload()
            val keywordOnly = noModel()
            val stored = if (keywordOnly) textOnlyProfile else store.profile() ?: requiredBuild.value.initialProfile()
            // Read per file, so a switch flipped mid-batch applies at once.
            val settings = {
                val p = batchPrefs()
                IndexSettings(p.effective(if (keywordOnly) textOnlyProfile else store.profile() ?: stored), p.kinds, p.paused)
            }
            val busy = { monitor.state().screenOn && !uiVisible }
            val requireCharging = { _: DeviceState -> readingBlocker() == StopReason.UNPLUGGED }
            val policyStop = { readingBlocker() ?: StopReason.MANUAL.takeIf { sessionOnly && store.prefs().readNowUntil <= now() } }
            policyStop()?.let { return end(it) }
            // The demo was stopped (its Continue, Skip, or Not now) while its scope stands: what it didn't finish waits for the
            // choice, whose wider scope takes it with the rest. MANUAL, so no follow-up is scheduled until then.
            if (store.sampleIds() != null && !sampleSession()) return end(StopReason.MANUAL)
            // Cancels cooperative extraction too, including a long PDF before its first embedding chunk.
            val owner = currentCoroutineContext().job
            // On the real clock, not [dispatchers]: a test's virtual time would spin this loop while the batch waits.
            watchdog = background.launch(Dispatchers.Default) {
                while (isActive) {
                    delay(1_000)
                    if (policyStop() != null) {
                        owner.cancel()
                        break
                    }
                }
            }
            // Under the first run's SAMPLE scope only the plan is work: the idle check, the ETA and the engine's pages.
            selectPace(factory.profileNow(stored, uiVisible))
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

            // An open app deepens once nothing is pending; in the background only with the screen off. The schedule gates both.
            fun canDeepen(): Boolean {
                val trialInFront = uiVisible && sampleSession()
                val current = readingMonitor.state()
                return (mayDeepen || trialInFront) && !keywordOnly && (!current.screenOn || uiVisible)
            }
            val deepen = canDeepen()
            // Nothing to read, no tail to embed, and vectors of the current model: opening it would do nothing.
            val idle = pending.values.sum() == 0L &&
                (!deepen || (if (ids == null) store.tailChunkCount() else store.tailCountsIn(ids).first) == 0L) &&
                (keywordOnly || store.signature() == Signature.of(requiredBuild.value, stored.visionTokens, stored.maxTokens)) &&
                // Pages or moments left for a deepen pass the absent model can't run: this batch resolves them.
                (!keywordOnly || store.laterChunkCount() == 0L)
            if (idle) return end(StopReason.DONE)
            val e = if (keywordOnly) {
                null
            } else {
                // A missing model file returns here, like the untuned case above.
                val (opened, b) = model.open() ?: return end(StopReason.DONE)
                used = opened
                build = b
                if (store.signature() != opened.signature) {
                    log("Model signature changed from ${store.signature()?.value} to ${opened.signature.value}; adding meaning to saved content", null)
                    store.resetForModel(opened.signature)
                }
                opened
            }
            val openedProfile = factory.profileNow(stored, uiVisible).copy(
                backend = e?.backend ?: Backend.CPU,
                visionBackend = e?.visionBackend ?: Backend.CPU,
            )
            selectPace(openedProfile)
            val configuration = model.currentConfiguration
            val changedConfiguration = {
                val current = factory.profileNow(store.profile() ?: stored, uiVisible)
                deepen != canDeepen() || (!keywordOnly && configuration != (current.backend to current.visionBackend))
            }
            val (left, videoBytes) = pendingNow()
            eta.start(left, videoBytes)
            _state.update { it.copy(pendingAtStart = left.values.sum(), etaMinutes = eta.minutes, filesPerMinute = eta.filesPerMinute) }
            val engine = if (ids == null) {
                IndexEngine(
                    store, extractor, e, readingMonitor, settings, ::onFile, busy, ::awaitUi,
                    onCurrent = ::onCurrent, configurationChanged = changedConfiguration, policyStop = policyStop,
                    readAheadDepth = { IndexEngine.readAheadDepth(it, uiVisible) }, readAheadContext = dispatchers.readAhead,
                )
            } else {
                val rank = ids.withIndex().associate { it.value to it.index }
                // Tails of planned files deepen after their heads; an empty plan stops DONE and nothing else is read.
                IndexEngine(
                    store, extractor, e, readingMonitor, settings, ::onFile, busy, ::awaitUi,
                    // One at a time, strictly in plan order.
                    pendingPage = { store.pendingIn(sampleIds.value.orEmpty()).sortedBy { rank[it.id] }.take(1) },
                    tailPage = { store.tailChunksIn(sampleIds.value.orEmpty()) },
                    onCurrent = ::onCurrent,
                    configurationChanged = changedConfiguration,
                    policyStop = policyStop,
                    readAheadDepth = { IndexEngine.readAheadDepth(it, uiVisible) },
                    readAheadContext = dispatchers.readAhead,
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
            error = "Something went wrong processing your files. Retriever will try again."
            return NextRun.RETRY
        } finally {
            watchdog?.cancel()
            saveBook()
            _state.update { it.copy(running = false, current = null, currentFile = null, lastStop = stop, lastError = error) }
            // Why each batch ended, so a phone that reads nothing says which rule stopped it.
            val device = monitor.state()
            val why = stop?.name ?: if (error != null) "error" else "cancelled"
            log(
                "Batch ended: $why after ${started.elapsedNow().inWholeSeconds} s, ${_state.value.processed - processedBefore} files; " +
                    "thermal ${device.thermal}, headroom ${device.headroom}, screen ${if (device.screenOn) "on" else "off"}, plugged ${device.pluggedIn}",
                null,
            )
        }
    }

    /** Reading words and labels never requires the optional model; audio is always name-only. */
    private fun batchPrefs(): Prefs = store.prefs().let { it.copy(kinds = it.kinds intersect readableKinds) }

    @Throws(Throwable::class)
    suspend fun meaningStates(fileIds: List<Long>): Map<Long, MeaningState> = io { store.meaningStates(fileIds) }

    private val readingMonitor = monitor

    /** Bumped whenever files come, go or change, so [home] reads again; reading files leaves it alone. */
    private val library = MutableStateFlow(0L)

    private fun libraryChanged() = library.update { it + 1 }

    /** After a sync: stars follow their files (hidden while a file is gone, back when it returns), and Home looks again. */
    private fun synced(result: SyncResult) {
        if (result.removed > 0 || result.added > 0) _starred.value = store.starredUris()
        if (result.removed > 0) reconcileRecentFiles()
        if (result.fullScan || result.added > 0 || result.changed > 0 || result.removed > 0) libraryChanged()
    }

    private fun reconcileRecentFiles() {
        _state.update { state -> state.copy(recentFiles = state.recentFiles.filter { store.fileByUri(it.uri) != null }) }
    }

    private fun onCurrent(file: SourceFile) {
        if (_prefs.value.caughtUpDismissed) _prefs.value = store.updatePrefs { it.readingStarted() }
        _state.update {
            it.copy(
                current = file.name,
                currentFile = file,
                etaMinutes = if (store.fileByUri(file.uri)?.state == FileState.INDEXED) null else it.etaMinutes,
            )
        }
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
                recentFiles = if (m.ok && store.fileByUri(m.file.uri)?.let { store.indexedIn(listOf(it.id)).isNotEmpty() } == true) {
                    (listOf(m.file) + it.recentFiles.filterNot { f -> f.uri == m.file.uri }).take(RECENT_FILES)
                } else {
                    it.recentFiles
                },
                filesPerMinute = eta.filesPerMinute,
                etaMinutes = eta.minutes,
            )
        }
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
            val (plan, rest) = planLocked(budget)
            rest.join()
            plan
        }
    }

    /**
     * [SetupStep.PLAN]: plans the sample once access is chosen, or, with nothing eligible to read, reads everything as files
     * arrive. A plan already made or a choice already taken is kept, so a second call does nothing. Returns once the plan's
     * files are written, so the sample starts reading while the rest of the library is written (unlike [planTaste]).
     */
    @Throws(Throwable::class)
    suspend fun planOrCommit() {
        val nothingToRead = indexLock.withLock {
            io {
                val s = loadSetup()
                if (s.decided || s.planned > 0) {
                    false
                } else {
                    planLocked(TASTE_BUDGET)
                    store.tasteIds().isEmpty()
                }
            }
        }
        if (nothingToRead) commitScope()
    }

    /**
     * Plans from the scan itself: a full scan hands its files over before any row is written, the plan's files are written
     * first, and the plan is set; the sync then writes the rest on its own (the returned job). An incremental sync plans
     * from the store once it ends, as every file is in it.
     */
    private suspend fun planLocked(budget: Duration): Pair<TastePlan, Job> {
        val planned = CompletableDeferred<TastePlan>()
        val rest = background.launch {
            try {
                synced(sync.sync(first = { scanned -> planned.complete(plan(scanned, budget)) }))
                if (!planned.isCompleted) planned.complete(plan(store.knownFiles().values, budget))
            } catch (e: CancellationException) {
                planned.cancel(e)
                throw e
            } catch (e: Exception) {
                if (!planned.completeExceptionally(e)) log("The library sync after the sample's plan failed", e)
            }
        }
        return planned.await() to rest
    }

    private fun plan(scanned: Collection<SourceFile>, budget: Duration): TastePlan {
        val kinds = store.prefs().kinds intersect readableKinds
        val files = scanned.filter { it.kind in kinds }
        store.profile()?.let { selectPace(factory.profileNow(it, uiVisible)) }
        val plan = FileRules.planTaste(files, eta.book::costMs, budget)
        store.setMeta(TASTE_BUDGET_KEY, budget.inWholeMilliseconds.toString())
        val picked = plan.ids.toHashSet()
        store.upsertFiles(files.filter { it.uri in picked })
        val byUri = store.filesByUris(plan.ids)
        store.setTaste(plan.ids.mapNotNull { byUri[it]?.id })
        loadScope()
        return plan
    }

    /** The short read: one batch of the planned files, which ends DONE once they and their tails are read. */
    @Throws(Throwable::class)
    suspend fun taste(): NextRun = indexBatch(TASTE_BATCH, waitForLock = true)

    @Throws(Throwable::class)
    suspend fun hasPendingTaste(): Boolean = io {
        // Paused, stopped or decided (Not now), the demo waits for the person, so screen-off and Battery Saver changes must not resume it.
        !store.prefs().paused && sampleSession() &&
            store.tasteIds().let { store.pendingIn(it).isNotEmpty() || (!noModel() && store.tailCountsIn(it).first > 0) }
    }

    /** Files to read or later parts to embed, so a batch would read something. */
    @Throws(Throwable::class)
    suspend fun hasWork(): Boolean = io { store.pendingByKind().values.sum() > 0 || (!noModel() && store.tailChunkCount() > 0) }

    /** The phone's power and heat now, for a platform deciding whether to hold a foreground run. */
    fun deviceState(): DeviceState = monitor.state()

    private val sharedTaste: Flow<TasteProgress> by lazy {
        watched(combine(store.countChanges(), sampleIds) { _, _ -> }, {}) {
            val ids = store.tasteIds()
            val planned = store.storedIn(ids).groupingBy { it.file.kind }.eachCount()
            val (pending, videoBytes) = store.pendingIn(ids).byKind()
            val searchable = store.indexedIn(ids).values.groupingBy { it.kind }.eachCount()
            TasteProgress.of(ids.size, planned, pending, videoBytes, eta.book, searchable, eta.shownRatio(tasteBudgetMs()))
        }
    }

    private fun tasteBudgetMs() = store.meta(TASTE_BUDGET_KEY)?.toDoubleOrNull() ?: TASTE_BUDGET.inWholeMilliseconds.toDouble()

    /** How far the short read is; re-emits as files are read. One shared flow; see [watched]. */
    fun tasteProgress(): Flow<TasteProgress> = sharedTaste

    private val sharedSample: Flow<List<SampleFile>> by lazy {
        watched(combine(store.countChanges(), sampleIds) { _, _ -> }, {}) {
            val ids = store.tasteIds()
            val byId = withExtents(store.storedIn(ids)).associateBy { it.id }
            val inside = readInside()
            ids.mapIndexedNotNull { i, id -> byId[id]?.let { SampleFile(i, it.file, it.contentRead, Words.coverage(it, inside)) } }
        }
    }

    /**
     * Every planned file in plan order, read or not, with its plan position and what of it is findable; re-emits as files
     * are read. One shared flow; see [watched].
     */
    fun sampleFiles(): Flow<List<SampleFile>> = sharedSample

    /** Read everything: scope ALL and the charging choice. The caller schedules the reading. */
    @Throws(Throwable::class)
    suspend fun commit(whileCharging: Boolean) {
        updatePrefs {
            (if (whileCharging) endSession(it).copy(paused = false) else startSession(it)).withSchedule(ReadingSchedule.CHARGING)
        }
        commitScope()
    }

    /**
     * Read everything, with nothing to choose (an empty plan, or a plan that already was everything): scope ALL and decided.
     * Never touches the schedule or a session, so the person's saved choice holds.
     */
    @Throws(Throwable::class)
    suspend fun commitScope() {
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
    suspend fun decideLater() {
        io {
            store.setFlag(TASTE_DECIDED_KEY)
            loadSetup()
        }
        // After the flag: the pause re-reads the counts, which then say Not now.
        pause()
    }

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

    /**
     * Closes the index database, for a facade that is done (tests make one per case); the app's lives as long as it does.
     * Stops the batch and the background sync first; the store then waits out any statement still running.
     */
    @Throws(Throwable::class)
    suspend fun close() {
        cancelBatch()
        background.coroutineContext.job.cancelAndJoin()
        store.close()
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
     * [onlyStarred] keeps starred files only. [scope] (the sample's [scopeIds]) narrows what is found by words and
     * meaning; names always cover the whole library.
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
     * relaxed: names alone finding nothing under a filter says nothing about what the full search will find. Names cover
     * the whole library, so [scope] changes nothing here; it is taken so both phases are called alike.
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
        val ids = starredIds(onlyStarred)
        // Nothing to search, so nothing by meaning is missing.
        if (ids != null && ids.isEmpty()) return SearchEngine.SearchResult(emptyList(), vectorLane = true, parsed = p)
        val parsedKinds = p.kinds.ifEmpty { null }
        val kinds = if (parsedKinds == null) uiKinds else uiKinds?.intersect(parsedKinds) ?: parsedKinds
        val req = SearchRequest(p.text, kinds, p.from, p.to, ids, limit, onlyStarred = onlyStarred, contentIds = scope?.toSet())
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
        return SearchEngine.SearchResult(SearchEngine.foldCopies(hits), vectorLane = true)
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

    /** Marks that the person has searched; the query is kept only while remembering searches is on. */
    @Throws(Throwable::class)
    suspend fun recordQuery(q: String) {
        if (!prefs.value.searchTried) updatePrefs { it.copy(searchTried = true) }
        io { if (store.prefs().rememberSearches) store.addRecentQuery(q, now()) }
    }

    @Throws(Throwable::class)
    suspend fun dismissUpgradeOffer() = updatePrefs { it.copy(upgradeOfferDismissed = true) }

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
    suspend fun starredFiles(limit: Int = 200, offset: Int = 0): List<Store.StoredFile> = io { withExtents(store.starredFiles(limit, offset)) }

    // --- Home ---

    private val sharedHome: Flow<HomeFacts> by lazy {
        watched(combine(library, starred) { _, _ -> }, {}) {
            val (largeCount, largeBytes) = store.largeFiles(LARGE_FILE)
            HomeFacts(
                newMedia = store.newestFiles(setOf(Kind.PHOTO, Kind.VIDEO), 15).map { it.file },
                newShots = store.newestFiles(setOf(Kind.SCREENSHOT), 10).map { it.file },
                newDocs = store.newestFiles(setOf(Kind.PDF, Kind.DOC), 8).map { it.file },
                starred = store.starredVisible(10).map { it.file },
                starredCount = store.starredVisibleCount(),
                folders = store.folderCount(),
                largeCount = largeCount,
                largeBytes = largeBytes,
                possibleCopies = store.sizeCollisionCount(DuplicateFinder.MIN_SIZE),
                kindTotals = store.kindTotals(),
                folderTotals = store.folderTotals(),
            )
        }
    }

    /**
     * Home's facts, again whenever files come, go or change, or a star does; never because a file was read. One shared
     * flow; see [watched].
     */
    fun home(): Flow<HomeFacts> = sharedHome

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

    private val suggestionLock = Mutex()
    private val contentSuggestions = ContentSuggestions()

    /**
     * Three things to search for from the content of [fileIds] (null: every file read), with document frequencies from
     * the whole read set; empty when there are not three good ones. Again as files are read, a burst at a time, while
     * collected. A failed read is logged and answers empty, then the flow ends, as [watched] does: it never reaches a
     * collector, and a screen waiting on suggestions still draws.
     */
    fun suggestions(fileIds: Set<Long>? = null): Flow<List<String>> = store.countChanges().conflate().map {
        suggestionLock.withLock {
            contentSuggestions.refresh(store.readContentVersions(), store::file) { id -> store.chunkTexts(id).mapNotNull { it.text } }
            contentSuggestions.suggestions(fileIds)
        }
    }.catch {
        log("Suggestions failed", it)
        emit(emptyList())
    }.distinctUntilChanged().flowOn(dispatchers.default)

    /** The kinds whose insides are read: the Settings switches, cut to what this phone can read. Others are found by name. */
    fun readInside(): Set<Kind> = _prefs.value.kinds intersect readableKinds

    /** The kinds this phone can read inside, and so the switches Settings shows. */
    val readableKinds: Set<Kind> get() = if (textOnly) textOnlyKinds else Kind.entries.toSet() - setOf(Kind.OTHER, Kind.AUDIO)

    @Throws(Throwable::class)
    suspend fun dismissCaughtUp() = updatePrefs { it.dismissCaughtUp() }

    @Throws(Throwable::class)
    suspend fun storedFile(uri: String): Store.StoredFile? = io { store.fileByUri(uri)?.let { withExtents(listOf(it)).first() } }

    private val _fileRevision = MutableStateFlow(0L)
    val fileRevision: StateFlow<Long> = _fileRevision.asStateFlow()

    /** Holds the scan lock across the platform rename and index update so a scan cannot replace the file id. */
    @Throws(Throwable::class)
    suspend fun renameFile(uri: String, operation: (SourceFile) -> SourceFile?): SourceFile? = io {
        indexLock.withLock {
            val old = store.fileByUri(uri)?.file ?: return@withLock null
            val changed = operation(old) ?: return@withLock null
            renamedFileLocked(uri, changed.name, changed.uri, changed.modifiedAt)
        }
    }

    private suspend fun renamedFileLocked(uri: String, name: String, newUri: String, modifiedAt: Long): SourceFile? {
        val file = sync.rename(uri, name, newUri, modifiedAt) ?: return null
        _state.update { it.copy(recentFiles = it.recentFiles.map { old -> if (old.uri == uri) file else old }) }
        _starred.value = store.starredUris()
        _fileRevision.update { it + 1 }
        libraryChanged()
        return file
    }

    @Throws(Throwable::class)
    suspend fun deletedFiles(uris: List<String>) = io {
        indexLock.withLock {
            sync.remove(uris)
            _fileRevision.update { it + 1 }
            libraryChanged()
            reconcileRecentFiles()
            _starred.value = store.starredUris()
        }
    }

    // --- Browse ---

    private fun visibleExtent(extent: ReadExtent): ReadExtent? = if (!noModel()) {
        extent
    } else {
        when (extent) {
            ReadExtent.DEEPENING, ReadExtent.MEANING_LIMITED -> null
            ReadExtent.FIRST_PART_DEEPENING, ReadExtent.FIRST_PART_MEANING_LIMITED -> ReadExtent.FIRST_PART
            else -> extent
        }
    }

    private fun withExtents(files: List<Store.StoredFile>): List<Store.StoredFile> {
        val extents = store.readExtents(files.map { it.id })
        return files.map { file -> file.copy(extent = extents[file.id]?.let(::visibleExtent)) }
    }

    @Throws(Throwable::class)
    suspend fun files(kind: Kind?, folder: String?, sort: Store.Sort, limit: Int, offset: Int): List<Store.StoredFile> =
        io { withExtents(store.files(kind, folder, sort, limit, offset)) }

    /** The photos and videos taken from [from] to [to] (ms, both ends in), oldest first: what a year card of On this day opens. */
    @Throws(Throwable::class)
    suspend fun filesTakenBetween(from: Long, to: Long, limit: Int, offset: Int): List<Store.StoredFile> =
        io { withExtents(store.filesTakenBetween(from, to, limit, offset)) }

    @Throws(Throwable::class)
    suspend fun folders(): List<Pair<String, Long>> = io { store.folders() }

    @Throws(Throwable::class)
    suspend fun duplicates(minSize: Long): List<DuplicateGroup> = io { DuplicateFinder(store, hasher).find(minSize) }

    @Throws(Throwable::class)
    suspend fun skipped(limit: Int): List<Store.StoredFile> = io { store.skipped(limit, store.prefs().kinds intersect readableKinds) }

    @Throws(Throwable::class)
    suspend fun retrySkipped() = io { store.retrySkipped() }

    @Throws(Throwable::class)
    suspend fun requeue(kinds: Set<Kind>) = io { store.requeue(kinds) }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(dispatchers.default) { block() }

    // --- Calibration ---

    /**
     * Tunes once more when a Tune turned the GPU down without a crash (a Pixel 8 Pro's first tune did, then passed it twice),
     * so one bad measure doesn't keep a phone on CPU. Only hidden on a charger, and once per phone. Null when not due.
     */
    @Throws(Throwable::class)
    suspend fun retestGpuIfDue(samples: suspend () -> Samples): CalibrationResult? {
        val due = io {
            val p = store.profile()
            !textOnly && !uiVisible && monitor.state().pluggedIn && readingBlocker() == null &&
                p != null && p.backend == Backend.CPU && p.gpuError != null &&
                store.meta(GPU_RETESTED) != "1" && modelReady(requiredBuild.value)
        }
        if (!due) return null
        io { store.setMeta(GPU_RETESTED, "1") }
        log("Tune turned the GPU down before; measuring it once more", null)
        return calibrate(samples(), tuneAgain = true)
    }

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
                    if (it.ok) "" else " (failed: ${it.error})"
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

        const val GPU_RETESTED = "gpu_retested"

        /** Long enough for a cancelled batch to unwind, short enough that a waiting job never nears its 10 minute limit. */
        val LOCK_WAIT = 30.seconds
        const val SPEED_KEY = "speed_by_kind_CPU_CPU"
        const val BG_SPEED_KEY = "bg_speed"
        const val SAVE_EVERY_MS = 30_000L

        /** The short read's plan, and the hard cap of its batch (onboarding §3.2). */
        val TASTE_BUDGET = 90.seconds
        val TASTE_BATCH = 120.seconds

        /** Photos whose median read time can move the tier up; an odd quick one alone can't. */
        const val RETIER_WINDOW = 5

        fun nextRunFor(reason: StopReason, changedSinceSync: Boolean): NextRun = when (reason) {
            StopReason.BUDGET -> NextRun.SOON
            StopReason.HOT -> NextRun.AFTER_COOLDOWN
            StopReason.POWER_SAVE -> NextRun.DONE
            StopReason.UNPLUGGED -> NextRun.WHEN_CHARGING
            StopReason.BUSY -> NextRun.WHEN_IDLE
            StopReason.DONE -> if (changedSinceSync) NextRun.SOON else NextRun.DONE
            StopReason.PAUSED, StopReason.MANUAL -> NextRun.DONE
            StopReason.LOW_BATTERY, StopReason.CHARGING_UP -> NextRun.WHEN_CHARGING
        }
    }
}
