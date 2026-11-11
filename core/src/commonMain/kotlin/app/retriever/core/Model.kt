package app.retriever.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

enum class Kind { PHOTO, SCREENSHOT, PDF, DOC, AUDIO, VIDEO, OTHER }

data class SourceFile(
    val uri: String,
    val name: String,
    val folder: String,
    val mime: String,
    val kind: Kind,
    val size: Long,
    val modifiedAt: Long,
    /** When a photo or video was taken, in ms; null when the source doesn't say. Last, so positional calls stay. */
    val takenAt: Long? = null,
)

sealed interface Part {
    data class Text(val text: String) : Part

    class Image(val jpeg: ByteArray) : Part

    /** Mono 16-bit PCM at [AUDIO_SAMPLE_RATE], the rate the model's audio frontend expects. */
    class Audio(val pcm: ShortArray) : Part {
        init {
            require(pcm.isNotEmpty()) { "empty audio" }
        }
    }
}

const val AUDIO_SAMPLE_RATE = 16_000

/** Upper cap; the real window is derived from the profile's token window (K4 VideoMoments.audioWindowSec). */
const val AUDIO_WINDOW_SEC = 10

/** A searchable slice of a file: a PDF page, an OCR'd screenshot, a 30 s audio window. */
data class Piece(val locator: String, val parts: List<Part>, val keywordText: String?, val contentText: String? = null, val labels: String? = null)

// Commit-pinned resolve URLs adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/data/ModelAllowlist.kt (Apache-2.0). Modified for Retriever: constants instead of a fetched allowlist.

/** Pinned to a Hugging Face commit so a re-export can never swap the file under existing vectors. */
enum class ModelVariant(
    val repo: String,
    val commit: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val vision: Boolean,
    val audio: Boolean,
) {
    M740(
        "litert-community/embeddinggemma-2-740m-litert-lm",
        "24d962e906c7d332c6428e71c9676855024569e2",
        "embeddinggemma-2-740m.litertlm",
        484_622_336,
        "e7a8a2204b91e0f96e92960e84a09a89212e1633dcb7575a9bf3378b4df77f4c",
        vision = true,
        audio = true,
    ),
    M440(
        "litert-community/embeddinggemma-2-text-vision-440m-litert-lm",
        "e301f74d5551b0c2641bd5cb4652a76239d5c5f8",
        "embeddinggemma-2-text-vision-440m.litertlm",
        387_710_976,
        "92dcbea108899e5d6e30d919b0744f90d9967e80c67a4ab5503ac16d54f62eb0",
        vision = true,
        audio = false,
    ),
    ;

    val generic: ModelBuild get() = ModelBuild(this, fileName, sizeBytes, sha256)
}

/** One downloadable file of a [variant]; [soc] is set for an NPU build compiled for that chip. */
data class ModelBuild(val variant: ModelVariant, val fileName: String, val sizeBytes: Long, val sha256: String, val soc: String? = null) {
    val url: String get() = "https://huggingface.co/${variant.repo}/resolve/${variant.commit}/$fileName"
    val isNpu: Boolean get() = soc != null
}

/** LiteRT-LM's cache directory value that keeps the compiled weights in memory, never on disk. */
const val NO_DISK_CACHE = ":nocache"

/**
 * Where the runtime may write [build]'s caches, given [freeBytes] free there. XNNPack aborts the app, with no error to
 * catch, when a cache write fails, and one CPU setup's caches take ~290 MB for M440's 388 MB. With less free than the
 * model's size they stay in memory: every open packs the weights again, slower but never out of space.
 */
fun cacheDirFor(build: ModelBuild, dir: String, freeBytes: Long): String = if (freeBytes < build.sizeBytes) NO_DISK_CACHE else dir

/** Conservative opening before optional tuning; an NPU build can run only on its own delegate. */
internal fun ModelBuild.initialProfile() = DeviceProfile(Tier.LIGHT, variant, if (isNpu) Backend.NPU else Backend.CPU, 70, 5, false, false)

enum class Backend { NPU, GPU, CPU }

/** How much of a file search can reach: all of it, all by keyword with meaning still being added, or its first part only. */
enum class ReadExtent { WHOLE, DEEPENING, FIRST_PART, FIRST_PART_DEEPENING, MEANING_LIMITED, FIRST_PART_MEANING_LIMITED }

enum class Tier { FULL, BALANCED, LIGHT }

@Serializable
data class DeviceProfile(
    val tier: Tier,
    val model: ModelVariant,
    val backend: Backend,
    val visionTokens: Int,
    val videoFrameEverySec: Int,
    val audio: Boolean,
    val bulkOnlyWhenCharging: Boolean,
    val maxTokens: Int = 512,
    // Keep earlier Tune results: vision stalls alone already justify moving every foreground delegate.
    @SerialName("gpuVisionStalls") val gpuStallsRendering: Boolean = false,
    val cpuImageMs: Double? = null,
    val gpuImageMs: Double? = null,
    /** Why Tune turned the GPU down, kept so a phone stuck on CPU shows the reason. */
    val gpuError: String? = null,
    /** Resolved for an open; null in the stored profile means follow the measured policy. */
    val visionBackend: Backend? = null,
)

enum class Thermal { NONE, LIGHT, MODERATE, SEVERE, CRITICAL }

/**
 * Heat policy. Android's thermal headroom (0 cool, 1.0 at SEVERE throttling) leads where the phone reports one: read-ahead
 * narrows from [AHEAD_NARROWS_AT] and stops at [AHEAD_STOPS_AT], and reading pauses at [PAUSE_AT] or SEVERE. Without one
 * (iOS, a phone with no thermal HAL) the status alone decides: LIGHT narrows read-ahead, SEVERE pauses. In between
 * (MODERATE, or past [AHEAD_STOPS_AT]) reading slows to half time instead of stopping: Android's MODERATE still leaves UX
 * "not largely impacted".
 */
object ThermalRest {
    /** A rest outside a foreground run (WorkManager's follow-up, iOS's loop): check again after this. */
    val RECHECK = 2.minutes

    /** For iOS, whose foreground loop sleeps this long after a cooldown (or a failed batch). */
    val RECHECK_SECONDS = RECHECK.inWholeSeconds

    /** Android documents NaN for headroom read more often; a foreground run's rest polls at this pace too. */
    val POLL = 10.seconds
    const val PAUSE_AT = 0.95f
    const val AHEAD_STOPS_AT = 0.85f
    const val AHEAD_NARROWS_AT = 0.7f
    const val MAX_READ_AHEAD = 2

    /** While warm, each file is followed by a rest as long as it took, up to this. */
    val WARM_REST_MAX = 10.seconds
}

/** [charging] is Android's "level rising", false for up to 15 minutes after plugging in below 90%; [pluggedIn] is on a charger. */
data class DeviceState(
    val ramGb: Double,
    val charging: Boolean,
    val batteryPct: Int,
    val thermal: Thermal,
    /** The phone is in use: on Android its screen is on and unlocked (a lit lock screen or charging screen saver is not use). */
    val screenOn: Boolean = false,
    val powerSave: Boolean = false,
    val pluggedIn: Boolean = charging,
    /** Android's thermal headroom forecast; null where the platform has none. */
    val headroom: Float? = null,
) {
    /** Screen-off on a charger nobody notices the heat, so reading runs on until the forecast reaches SEVERE (1.0). */
    private val pauseAt: Float get() = if (!screenOn && pluggedIn) 1f else ThermalRest.PAUSE_AT

    val hot: Boolean get() = thermal >= Thermal.SEVERE || headroom.let { it != null && it >= pauseAt }

    /** Short of [hot] but throttling: reading rests between files ([ThermalRest.WARM_REST_MAX]). */
    val warm: Boolean get() = !hot && (thermal >= Thermal.MODERATE || headroom.let { it != null && it >= ThermalRest.AHEAD_STOPS_AT })

    /** How many files heat allows to be read ahead (see [ThermalRest]). */
    val aheadRoom: Int get() {
        if (hot) return 0
        val byStatus = when (thermal) {
            Thermal.NONE -> ThermalRest.MAX_READ_AHEAD
            Thermal.LIGHT -> 1
            else -> 0
        }
        val byHeadroom = when {
            headroom == null || headroom < ThermalRest.AHEAD_NARROWS_AT -> ThermalRest.MAX_READ_AHEAD
            headroom < ThermalRest.AHEAD_STOPS_AT -> 1
            else -> 0
        }
        return minOf(byStatus, byHeadroom)
    }

    /** Cool enough to end a rest: read-ahead would run again, so the next file doesn't land back at the pause line. */
    val cooled: Boolean get() = aheadRoom > 0
}

enum class Platform { ANDROID, IOS }

private val RAM_SIZES = listOf(1, 2, 3, 4, 6, 8, 12, 16, 24, 32)

/**
 * Memory rounded up to the marketed size, so the OS-visible total (5.5e9 B on a 6 GB phone, about 7 GiB on an
 * 8 GB one, 5.6 GiB on a 6 GB iPhone) meets the thresholds in [Tiers] and [ModelBuilds] as advertisedMem does.
 */
fun nominalRamGb(bytes: Long): Double {
    val gib = bytes.toDouble() / (1L shl 30)
    return (RAM_SIZES.firstOrNull { it >= gib - 0.01 } ?: kotlin.math.ceil(gib).toInt()).toDouble()
}

data class DeviceProbe(
    val platform: Platform,
    val sdkInt: Int,
    val socManufacturer: String,
    val socModel: String,
    val ramGb: Double,
    val oldMaliGpu: Boolean,
    val npuDispatch: Boolean = false,
)
