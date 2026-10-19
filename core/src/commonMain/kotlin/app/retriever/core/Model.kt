package app.retriever.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.minutes

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
    /** Resolved for an open; null in the stored profile means follow the measured policy. */
    val visionBackend: Backend? = null,
)

enum class Thermal { NONE, LIGHT, MODERATE, SEVERE, CRITICAL }

/** Indexing stops at MODERATE and a rest check resumes it at LIGHT or lower, so a brief spike costs minutes, not a fixed quarter hour. */
object ThermalRest {
    val RECHECK = 2.minutes

    /** For iOS, whose foreground loop sleeps this long after a cooldown (or a failed batch). */
    val RECHECK_SECONDS = RECHECK.inWholeSeconds
    val STOP_AT = Thermal.MODERATE
}

data class DeviceState(
    val ramGb: Double,
    val charging: Boolean,
    val batteryPct: Int,
    val thermal: Thermal,
    val screenOn: Boolean = false,
    val powerSave: Boolean = false,
) {
    val hot: Boolean get() = thermal >= ThermalRest.STOP_AT
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
