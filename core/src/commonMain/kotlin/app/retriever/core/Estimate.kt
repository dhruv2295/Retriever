package app.retriever.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile
import kotlin.math.abs
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
            videoBytes * (p?.takeIf { it.units > 0 }?.let { it.ms / it.units } ?: SEED_MS_PER_VIDEO_BYTE)
        } else {
            files * (p?.let { it.ms / it.files } ?: seedMsPerFile(kind))
        }
    }

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
    val measured = pending.filterValues { it > 0 }.keys.all { it in book.paces }
    return ReadEstimate(files, floor(minutes * ESTIMATE_LOW).toInt(), high, nights, measured)
}

/**
 * The live ETA. [book] outlives batches; what is left is read at each batch's start and counted down per file. The
 * minutes stay null until [SHOW_AFTER_MS] of work this session, and the shown value moves only past [moved].
 */
internal class Eta(@Volatile var book: SpeedBook) {
    private var left = mutableMapOf<Kind, Long>()
    private var videoBytes = 0L
    private var sessionMs = 0.0
    private var sessionFiles = 0

    var minutes: Int? = null
        private set

    /** Files a minute of work this session. */
    val filesPerMinute: Double get() = if (sessionMs > 0) sessionFiles * 60_000 / sessionMs else 0.0

    fun start(pending: Map<Kind, Long>, videoBytes: Long) {
        left = pending.toMutableMap()
        this.videoBytes = videoBytes
        update()
    }

    fun add(kind: Kind, ms: Double, units: Double) {
        book = book.add(kind, ms, units)
        sessionMs += ms
        sessionFiles++
        left[kind] = ((left[kind] ?: 0L) - 1).coerceAtLeast(0)
        if (kind == Kind.VIDEO) videoBytes = (videoBytes - units.toLong()).coerceAtLeast(0)
        update()
    }

    private fun update() {
        if (sessionMs < SHOW_AFTER_MS) return
        val now = ceil(book.ms(left, videoBytes) / 60_000).toInt()
        if (moved(minutes, now)) minutes = now
    }

    companion object {
        const val SHOW_AFTER_MS = 30_000.0

        /** More than 10%, and at least 2 minutes: a tolerance relative to the value with an absolute floor. */
        fun moved(shown: Int?, now: Int): Boolean = shown == null || abs(now - shown) > max(0.1 * shown, 2.0)
    }
}

/** Placeholders until one full read on a device is timed against its own short read (build plan WP-P1). */
const val ESTIMATE_LOW = 0.9
const val ESTIMATE_HIGH = 1.4

/** While false, no UI may show hours from [readEstimate]: use the fallback copy. */
const val ESTIMATE_CALIBRATED = false

/** One charging night, INFERRED (onboarding §3.4). */
const val NIGHT_MINUTES = 360.0

// Seeds for a kind not measured yet, INFERRED: photos ~480 ms and docs 100-200 ms in the soak; the rest are guesses
// (a 100 MB video is about a minute, 12 frames at 500 ms). Measured time replaces them at the first file of a kind.
private val SEED_MS_PER_FILE = mapOf(
    Kind.PHOTO to 500.0,
    Kind.SCREENSHOT to 500.0,
    Kind.PDF to 300.0,
    Kind.DOC to 200.0,
    Kind.AUDIO to 5_000.0,
    Kind.VIDEO to 0.0,
    Kind.OTHER to 20.0,
)
private const val SEED_MS_PER_VIDEO_BYTE = 6e-5

private val JSON = Json { ignoreUnknownKeys = true }
