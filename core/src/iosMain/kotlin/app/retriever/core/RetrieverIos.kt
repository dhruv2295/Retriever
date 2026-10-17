@file:OptIn(ExperimentalForeignApi::class)

package app.retriever.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

fun createRetriever(
    opener: NativeEmbedderOpener,
    extractor: NativeExtractor,
    photos: NativeFileSource,
    folders: NativeFileSource,
    monitor: IosDeviceMonitor,
    modelDir: String,
    cacheDir: String,
    isSimulator: Boolean,
    platformReady: () -> Boolean,
    log: (String) -> Unit,
): Retriever {
    val logged: (String, Throwable?) -> Unit = { m, t -> log(if (t == null) m else "$m: $t") }
    val store = iosStore(logged)
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

@Throws(Throwable::class)
suspend fun Retriever.searchImageData(jpeg: NSData, kinds: Set<Kind>?, limit: Int, onlyStarred: Boolean): SearchEngine.SearchResult =
    searchImage(jpeg.toByteArray(), kinds, limit, onlyStarred)

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
    return object : AutoCloseable {
        override fun close() = scope.cancel()
    }
}
