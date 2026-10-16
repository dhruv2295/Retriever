package app.rummage.core

import kotlinx.serialization.Serializable

enum class Kind { PHOTO, SCREENSHOT, PDF, DOC, AUDIO, VIDEO, OTHER }

data class SourceFile(
    val uri: String,
    val name: String,
    val folder: String,
    val mime: String,
    val kind: Kind,
    val size: Long,
    val modifiedAt: Long,
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

/** 10 s of audio is ~250 model tokens, which keeps every input inside a 512-token signature. */
const val AUDIO_WINDOW_SEC = 10

/** Longest input we let the model see. Inputs are padded to the signature length, so shorter is faster. */
const val MAX_INPUT_TOKENS = 512

/** A searchable slice of a file: a PDF page, an OCR'd screenshot, a 30 s audio window. */
data class Piece(
    val locator: String,
    val parts: List<Part>,
    val keywordText: String?,
)

enum class ModelVariant(val fileName: String, val vision: Boolean, val audio: Boolean) {
    M740("embeddinggemma-2-740m.litertlm", vision = true, audio = true),
    M440("embeddinggemma-2-text-vision-440m.litertlm", vision = true, audio = false),
    M270("embeddinggemma-2-text-270m.litertlm", vision = false, audio = false),
}

enum class Backend { NPU, GPU, CPU }

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
)

enum class Thermal { NONE, LIGHT, MODERATE, SEVERE, CRITICAL }

data class DeviceState(
    val ramGb: Double,
    val charging: Boolean,
    val batteryPct: Int,
    val thermal: Thermal,
) {
    val hot: Boolean get() = thermal >= Thermal.MODERATE
}
