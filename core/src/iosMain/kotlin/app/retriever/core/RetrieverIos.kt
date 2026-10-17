@file:OptIn(ExperimentalForeignApi::class)

package app.retriever.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSTimeZone
import platform.Foundation.localTimeZone
import platform.Foundation.secondsFromGMT
import platform.Foundation.dataWithContentsOfFile
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
    log: (String) -> Unit,
): Retriever {
    val logged: (String, Throwable?) -> Unit = { m, t -> log(if (t == null) m else "$m: $t") }
    val store = iosStore(dataDir, logged)
    val known = KnownFiles { store.fileByUri(it)?.file }
    return Retriever(
        store = store,
        source = CompositeSource(mapOf("ph://" to photos.asFileSource(known), "folder://" to folders.asFileSource(known))),
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

/**
 * The person's continued read: pending files only, so it ends DONE once they are read, and it waits for the
 * foreground batch it replaces to stop rather than finding the index busy.
 */
@Throws(Throwable::class)
suspend fun Retriever.continuedBatchSeconds(seconds: Int): NextRun = indexBatch(seconds.seconds, deepen = false, waitForLock = true)

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
)

/** Everything the UI watches, as one callback on a background thread; Swift hops to the main actor itself. */
fun Retriever.observeAll(cb: (RetrieverSnapshot) -> Unit): AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    scope.launch {
        combine(setUp, prefs, requiredBuild, counts(), combine(state, starred, modelMissing, ::Triple)) { s, p, b, c, (i, st, m) ->
            RetrieverSnapshot(s, p, b, c, i, st, m)
        }.collect { cb(it) }
    }
    return AutoCloseable { scope.cancel() }
}
