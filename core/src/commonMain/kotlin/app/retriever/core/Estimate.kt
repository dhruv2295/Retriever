package app.retriever.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Time spent per kind, summed across batches and launches. Sums of time over sums of work, never an average of rates:
 * averaging per-file rates lets one 50 ms file weigh as much as twenty slow ones (soak A1). A unit is a chunk for
 * PDF and DOC, a byte for VIDEO and a file otherwise.
 */
@Serializable
data class SpeedBook(val paces: Map<Kind, Pace> = emptyMap(), val photoSeedMs: Double? = null) {
    @Serializable
    data class Pace(val ms: Double, val units: Double, val files: Long)

    fun add(kind: Kind, ms: Double, units: Double): SpeedBook {
        val p = paces[kind]
        return copy(paces = paces + (kind to Pace((p?.ms ?: 0.0) + ms, (p?.units ?: 0.0) + units, (p?.files ?: 0) + 1)))
    }

    /**
     * Pending files × units per file × ms per unit, which is files × ms per file for every kind but VIDEO, read by its
     * pending [videoBytes]. A kind not measured yet takes its seed.
     */
    fun ms(pending: Map<Kind, Long>, videoBytes: Long): Double = pending.entries.sumOf { (kind, files) ->
        val p = paces[kind]
        if (kind == Kind.VIDEO) {
            max(
                files * (p?.let { it.ms / it.files } ?: VIDEO_SEED_MS),
                videoBytes * (p?.takeIf { it.units > 0 }?.let { it.ms / it.units } ?: SEED_MS_PER_VIDEO_BYTE),
            )
        } else {
            files * (p?.let { it.ms / it.files } ?: seedMsPerFile(kind))
        }
    }

    fun costMs(file: SourceFile): Double = ms(mapOf(file.kind to 1L), file.size)

    private fun seedMsPerFile(kind: Kind): Double = photoSeedMs?.takeIf { kind == Kind.PHOTO || kind == Kind.SCREENSHOT } ?: SEED_MS_PER_FILE.getValue(kind)

    fun encode(): String = JSON.encodeToString(this)

    companion object {
        fun decode(s: String?): SpeedBook = s?.let { runCatching { JSON.decodeFromString<SpeedBook>(it) }.getOrNull() } ?: SpeedBook()

        fun units(m: FileMetric): Double = when (m.file.kind) {
            Kind.PDF, Kind.DOC -> m.chunks.toDouble()
            Kind.VIDEO -> m.file.size.toDouble()
            else -> 1.0
        }
    }
}

/** What background runs have read, for iOS's nights: BGProcessingTask's nightly time isn't known until measured. */
@Serializable
data class BgSpeed(val files: Long = 0, val seconds: Long = 0, val runs: Int = 0) {
    fun add(files: Int, seconds: Long) = BgSpeed(this.files + files, this.seconds + seconds, runs + 1)

    fun encode(): String = JSON.encodeToString(this)

    companion object {
        fun decode(s: String?): BgSpeed? = s?.let { runCatching { JSON.decodeFromString<BgSpeed>(it) }.getOrNull() }
    }
}

/** How long the rest of the library takes at this phone's measured pace, as a range; see [ESTIMATE_CALIBRATED]. */
data class ReadEstimate(val files: Long, val lowMin: Int, val highMin: Int, val nightsOnCharger: Int?, val measured: Boolean)

/**
 * Null until a file has been measured. [measured] is false while a pending kind still runs on its seed. Android counts
 * 6-hour charging nights over the high end; iOS counts measured background files per night, null before the first run.
 */
fun readEstimate(book: SpeedBook, pending: Map<Kind, Long>, videoBytes: Long, ios: Boolean, bg: BgSpeed?): ReadEstimate? {
    if (book.paces.isEmpty()) return null
    val minutes = book.ms(pending, videoBytes) / 60_000
    val high = ceil(minutes * ESTIMATE_HIGH).toInt()
    val files = pending.values.sum()
    val nights = if (ios) {
        bg?.takeIf { it.files > 0 }?.let { ceil(files * it.runs / it.files.toDouble()).toInt() }
    } else {
        ceil(high / NIGHT_MINUTES).toInt()
    }
    return ReadEstimate(files, floor(minutes * ESTIMATE_LOW).toInt(), high, nights, book.measures(pending))
}

/** Every kind still to read has a measured pace, so [SpeedBook.ms] runs on no seed. */
internal fun SpeedBook.measures(pending: Map<Kind, Number>): Boolean = pending.filterValues { it.toLong() > 0 }.keys.all { it in paces }

/**
 * This session's pace: [book] outlives batches and launches, and the wall clock against inference time ([wallRatio])
 * scales the short read's time left ([TasteProgress]).
 */
internal class Eta(@Volatile var book: SpeedBook, private val now: () -> Long) {
    private var sessionMs = 0.0
    private var sessionFiles = 0
    private var lastAt: Long? = null

    /**
     * This session's reading in wall time: each file from the end of the one before it in the same batch, the batch's
     * first by its own time. So the model's open and the time between batches (a pause, the app away) never count.
     */
    @Volatile
    var wallMs = 0.0
        private set

    /** Wall time per ms of inference this session: the screen, the database and the rest of the phone included. */
    @Volatile
    var wallRatio: Double? = null
        private set

    /**
     * [wallRatio] once [SHOW_AFTER_BUDGET_SHARE] of [budgetMs] in wall time and a gap between two files stand behind it
     * (a batch's first file alone is exactly 1), so no warm-up number is shown. Scaled to the budget, not a fixed span of
     * inference: a slow phone trims its sample to fewer inference seconds, and a fixed span would never be reached.
     */
    fun shownRatio(budgetMs: Double): Double? = wallRatio?.takeIf { sessionFiles > 1 && wallMs >= budgetMs * SHOW_AFTER_BUDGET_SHARE }

    fun changeBook(book: SpeedBook) {
        this.book = book
        sessionMs = 0.0
        sessionFiles = 0
        wallMs = 0.0
        wallRatio = null
    }

    /** A batch starts: the wall clock waits for its first file. */
    fun start() {
        lastAt = null
    }

    fun add(kind: Kind, ms: Double, units: Double) {
        val at = now()
        wallMs += lastAt?.let { (at - it).toDouble() } ?: ms
        lastAt = at
        book = book.add(kind, ms, units)
        sessionMs += ms
        sessionFiles++
        wallRatio = if (sessionMs > 0) wallMs / sessionMs else null
    }

    companion object {
        /** The share of the sample's budget, in wall time, read before its time left shows: the rest of it shows a time. */
        const val SHOW_AFTER_BUDGET_SHARE = 0.25
    }
}

/** Placeholders until one full read on a device is timed against its own short read (build plan WP-P1). */
const val ESTIMATE_LOW = 0.9
const val ESTIMATE_HIGH = 1.4

/** While false, no UI may show hours from [readEstimate]: use the fallback copy. */
const val ESTIMATE_CALIBRATED = false

/** One charging night, INFERRED (onboarding §3.4). */
const val NIGHT_MINUTES = 360.0

// Pixel trial: photos 0.6–0.8 s, screenshots ~1 s, videos ~25 s. Byte size alone misses decode/frame overhead.
// Large unmeasured videos allow about a minute per 100 MB; measured time replaces the seeds.
internal const val VIDEO_SEED_MS = 25_000.0
private val SEED_MS_PER_FILE = mapOf(
    Kind.PHOTO to 800.0,
    Kind.SCREENSHOT to 1000.0,
    Kind.PDF to 300.0,
    Kind.DOC to 200.0,
    Kind.AUDIO to 5_000.0,
    Kind.VIDEO to 0.0,
    Kind.OTHER to 20.0,
)
private const val SEED_MS_PER_VIDEO_BYTE = 6e-4

private val JSON = Json { ignoreUnknownKeys = true }
