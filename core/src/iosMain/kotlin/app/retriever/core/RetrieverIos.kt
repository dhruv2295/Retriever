@file:OptIn(ExperimentalForeignApi::class)

package app.retriever.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSTimeZone
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.localTimeZone
import platform.Foundation.secondsFromGMT
import platform.Foundation.timeIntervalSince1970
import kotlin.time.Duration.Companion.seconds

// Swift entry points. Swift can't implement suspend members, pass a Duration or collect a Flow, so the app goes
// through these.

/** [dataDir] holds the index database: [appDataDir] in the app, a temporary directory in tests. */
fun createRetriever(
    opener: NativeEmbedderOpener,
    extractor: NativeExtractor,
    photos: NativeFileSource,
    folders: NativeFileSource,
    monitor: IosDeviceMonitor,
    modelDir: String,
    cacheDir: String,
    dataDir: String,
    isSimulator: Boolean,
    platformReady: () -> Boolean,
    words: DeviceWords,
    log: (String) -> Unit,
): Retriever {
    val logged: (String, Throwable?) -> Unit = { m, t -> log(if (t == null) m else "$m: $t") }
    val store = iosStore(dataDir, logged)

    // Each changes() call gets one map of the stored files, loaded on its first lookup, instead of a query per URI.
    fun NativeFileSource.snapshotting() = object : NativeFileSource {
        override fun changes(since: String?, known: KnownFiles): Changes {
            val files = lazy { store.knownFiles() }
            return this@snapshotting.changes(since) { files.value[it] }
        }
    }.asFileSource { null }
    return Retriever(
        store = store,
        source = CompositeSource(mapOf("ph://" to photos.snapshotting(), "folder://" to folders.snapshotting())),
        extractor = IosExtractor(extractor),
        factory = IosEmbedderFactory(opener, modelDir, cacheDir, isSimulator) { monitor.active },
        monitor = monitor,
        hasher = IosContentHasher(extractor),
        probe = iosDeviceProbe(),
        modelReady = { modelReady(modelDir, it) },
        platformReady = platformReady,
        log = logged,
        now = { (NSDate().timeIntervalSince1970 * 1000).toLong() },
        zoneOffsetMs = { NSTimeZone.localTimeZone.secondsFromGMT * 1000L },
        dispatchers = CoreDispatchers(indexing = indexingDispatcher),
        // iOS keeps no exit reasons the app can read at launch, so every unfinished open counts as a crash.
        lastExitCrashed = { true },
        frames = IosFrameSource(),
        words = words,
    )
}

/** The downloaded file is whole and verified: the same contract as Android's ModelDownload.ready. */
fun modelReady(modelDir: String, build: ModelBuild): Boolean {
    val path = "$modelDir/${build.fileName}"
    val size = (NSFileManager.defaultManager.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? NSNumber)?.longLongValue
    if (size != build.sizeBytes) return false
    val marker = NSData.dataWithContentsOfFile("$path.sha256")?.toByteArray()?.decodeToString()
    return marker?.trim() == build.sha256
}

/** indexBatch(Duration) exports as a raw int64_t, so Swift passes seconds. */
@Throws(Throwable::class)
suspend fun Retriever.indexBatchSeconds(seconds: Int): NextRun = indexBatch(seconds.seconds)

/** A comeback waits for the cancelled background batch, so lock contention cannot put the visible loop to sleep. */
@Throws(Throwable::class)
suspend fun Retriever.foregroundBatchSeconds(seconds: Int): NextRun = indexBatch(seconds.seconds, waitForLock = true)

/**
 * The person's continued read: pending files only, so it ends DONE once they are read, and it waits for the
 * foreground batch it replaces to stop rather than finding the index busy.
 */
@Throws(Throwable::class)
suspend fun Retriever.continuedBatchSeconds(seconds: Int): NextRun = indexBatch(seconds.seconds, waitForLock = true, sessionOnly = true)

// Swift's async import of a suspend function doesn't cancel the Kotlin call, so a superseded search kept running and
// held the embedder mutex. These hand Swift a handle whose close() cancels the job; done runs exactly once, with a
// CancellationException when closed.

fun Retriever.searchCancellable(
    q: String,
    kinds: Set<Kind>?,
    limit: Int,
    onlyStarred: Boolean,
    literal: Boolean,
    done: (SearchEngine.SearchResult?, Throwable?) -> Unit,
): AutoCloseable = cancellable({ search(q, kinds, limit, onlyStarred, literal) }, done)

fun Retriever.searchNamesCancellable(
    q: String,
    kinds: Set<Kind>?,
    limit: Int,
    onlyStarred: Boolean,
    literal: Boolean,
    done: (SearchEngine.SearchResult?, Throwable?) -> Unit,
): AutoCloseable = cancellable({ searchNames(q, kinds, limit, onlyStarred, literal) }, done)

fun Retriever.searchImageDataCancellable(
    jpeg: NSData,
    kinds: Set<Kind>?,
    limit: Int,
    onlyStarred: Boolean,
    done: (SearchEngine.SearchResult?, Throwable?) -> Unit,
): AutoCloseable = cancellable({ searchImage(jpeg.toByteArray(), kinds, limit, onlyStarred) }, done)

/** Internal: a generic T reaches Swift as id, and Swift can't build a Kotlin suspend lambda. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> cancellable(block: suspend () -> T, done: (T?, Throwable?) -> Unit): AutoCloseable {
    // async keeps a failure in the Deferred instead of the uncaught handler; the completion handler runs even when
    // close() comes before the block starts.
    val job = CoroutineScope(Dispatchers.Default).async { block() }
    job.invokeOnCompletion { cause -> if (cause == null) done(job.getCompleted(), null) else done(null, cause) }
    return AutoCloseable { job.cancel() }
}

// Typed reads: StateFlow is an interface, so its value reaches Swift as Any?.
val Retriever.prefsNow: Prefs get() = prefs.value
val Retriever.requiredBuildNow: ModelBuild get() = requiredBuild.value
val Retriever.stateNow: IndexState get() = state.value
val Retriever.setUpNow: Boolean? get() = setUp.value
val Retriever.modelMissingNow: Boolean get() = modelMissing.value
val Retriever.setupNow: SetupInputs? get() = setup.value

/** Calibration samples from Swift; iOS ships no audio model. */
fun samples(images: List<NSData>, texts: List<String>): Samples = Samples(images.map { it.toByteArray() }, texts, emptyList())

data class RetrieverSnapshot(
    val setUp: Boolean?,
    val prefs: Prefs,
    val requiredBuild: ModelBuild,
    val counts: Store.Counts,
    val state: IndexState,
    val starred: Set<String>,
    val modelMissing: Boolean,
    val reading: ReadingStatus,
    /** Add Photos and folder access with [SetupInputs.withAccess]. */
    val setup: SetupInputs?,
)

/** [flow]'s values on a background thread until closed; the work behind it runs only while some observer is open. */
private fun <T> observe(flow: Flow<T>, cb: (T) -> Unit): AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    scope.launch { flow.collect { cb(it) } }
    return AutoCloseable { scope.cancel() }
}

/** The short read's progress, as [observeAll] delivers its snapshots. */
fun Retriever.observeTasteProgress(cb: (TasteProgress) -> Unit): AutoCloseable = observe(tasteProgress(), cb)

/** The sample's planned files, as [observeAll] delivers its snapshots. */
fun Retriever.observeSampleFiles(cb: (List<SampleFile>) -> Unit): AutoCloseable = observe(sampleFiles(), cb)

/** Home's facts ([Retriever.home]); open it while Home shows, close it when Home goes. */
fun Retriever.observeHome(cb: (HomeFacts) -> Unit): AutoCloseable = observe(home(), cb)

/** [Retriever.suggestions] of [fileIds] (null: every file read); open it while the suggestions show. */
fun Retriever.observeSuggestions(fileIds: Set<Long>?, cb: (List<String>) -> Unit): AutoCloseable = observe(suggestions(fileIds), cb)

/** [Retriever.suggestions] of [fileIds] once, for a view that asks again on its own key (Try, as files are read). */
@Throws(Throwable::class)
suspend fun Retriever.suggestionsNow(fileIds: Set<Long>?): List<String> = suggestions(fileIds).first()

/** Everything the UI watches, as one callback on a background thread; Swift hops to the main actor itself. */
fun Retriever.observeAll(cb: (RetrieverSnapshot) -> Unit): AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    scope.launch {
        combine(
            setUp,
            prefs,
            requiredBuild,
            counts(),
            combine(state, starred, modelMissing, readingStatus(), setup) { i, st, m, reading, setup ->
                Triple(i, st, m) to (reading to setup)
            },
        ) { s, p, b, c, (live, rest) ->
            RetrieverSnapshot(s, p, b, c, live.first, live.second, live.third, rest.first, rest.second)
        }.collect { cb(it) }
    }
    return AutoCloseable { scope.cancel() }
}
