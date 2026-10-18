package app.retriever.core

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

const val DIMS = 256

/**
 * Which model and settings produced a vector. Vectors with different signatures never mix. A data class, not
 * a value class, so Swift sees a real type and can build one with `Signature.companion.of`.
 */
data class Signature(val value: String) {
    companion object {
        /** The sha prefix keys vectors to the loaded file, so a new export or an NPU build never mixes. */
        fun of(build: ModelBuild, visionTokens: Int, maxTokens: Int) =
            Signature("${build.variant.name}/${build.sha256.take(8)}/vt$visionTokens/d$DIMS/t$maxTokens")
    }
}

/**
 * A stored embedding: the first [DIMS] Matryoshka dimensions, re-normalised, as int8.
 * value[i] ≈ bytes[i] * scale.
 */
class Vector(val bytes: ByteArray, val scale: Float, val signature: Signature) {
    companion object {
        fun of(raw: FloatArray, signature: Signature): Vector {
            require(raw.size >= DIMS) { "model returned ${raw.size} dims, need $DIMS" }
            require(raw.none { it.isNaN() }) { "model returned NaN" }
            val cut = normalized(raw.copyOf(DIMS))
            val maxAbs = cut.maxOf { abs(it) }.coerceAtLeast(1e-12f)
            val scale = maxAbs / 127f
            val bytes = ByteArray(DIMS) { (cut[it] / scale).roundToInt().coerceIn(-127, 127).toByte() }
            return Vector(bytes, scale, signature)
        }
    }
}

fun normalized(v: FloatArray): FloatArray {
    var sum = 0.0
    for (x in v) sum += x * x
    val norm = sqrt(sum).toFloat().coerceAtLeast(1e-12f)
    return FloatArray(v.size) { v[it] / norm }
}

/** Cosine between two raw model outputs, compared on their first [dims] (Matryoshka) dimensions. */
fun cosine(a: FloatArray, b: FloatArray, dims: Int = DIMS): Float {
    val x = normalized(a.copyOf(dims))
    val y = normalized(b.copyOf(dims))
    var dot = 0f
    for (i in 0 until dims) dot += x[i] * y[i]
    return dot
}

/** Dot product of two int8 vectors; both are unit length, so this is their cosine. */
fun dotInt8(a: ByteArray, aOff: Int, b: ByteArray, bScale: Float, aScale: Float): Float {
    var acc = 0
    for (i in 0 until DIMS) acc += a[aOff + i] * b[i]
    return acc * aScale * bScale
}

/**
 * What a row's vector was made from, so statistics over cosines never mix populations (text-to-image cosines sit in
 * another band from text-to-text ones). Derived from what the chunk row keeps: the name vector (ordinal 0) and words
 * are text; with no words, audio files are audio, video moments are a frame with its timestamp text (and sound), and
 * the rest are images. Known gap: a screenshot or page image with too few words to make text chunks keeps those words
 * on its image row, so it reads as TEXT here.
 */
enum class Modality {
    TEXT,
    IMAGE,
    IMAGE_TEXT,
    AUDIO,
    ;

    companion object {
        fun of(kind: Kind, hasText: Boolean, ordinal: Long): Modality = when {
            hasText || ordinal == 0L -> TEXT
            kind == Kind.AUDIO -> AUDIO
            kind == Kind.VIDEO -> IMAGE_TEXT
            else -> IMAGE
        }
    }
}

/** Vectors held in flat arrays for the scan; grows with doubling capacity so appends stay cheap. */
class VectorTable(
    chunkIds: LongArray,
    fileIds: LongArray,
    bytes: ByteArray,
    scales: FloatArray,
    size: Int = chunkIds.size,
    /** [Kind] ordinal per row, so a kind filter applies inside the scan instead of after the top-k cut. */
    kinds: ByteArray = ByteArray(chunkIds.size),
    /** [Modality] ordinal per row; its own array, since the scan indexes its kind filter by the raw kind byte. */
    modalities: ByteArray = ByteArray(chunkIds.size),
    /** Capture day per row: COALESCE(taken_at, modified_at) as whole UTC days since 1970. */
    days: IntArray = IntArray(chunkIds.size),
) {
    var chunkIds = chunkIds
        private set
    var fileIds = fileIds
        private set
    var bytes = bytes
        private set
    var scales = scales
        private set
    var kinds = kinds
        private set
    var modalities = modalities
        private set
    var days = days
        private set
    var size = size
        private set

    val lastId: Long get() = if (size == 0) 0L else chunkIds[size - 1]

    internal fun ensureCapacity(n: Int): VectorTable {
        if (n <= chunkIds.size) return this
        val cap = maxOf(n, chunkIds.size * 2, 1024)
        chunkIds = chunkIds.copyOf(cap)
        fileIds = fileIds.copyOf(cap)
        bytes = bytes.copyOf(cap * DIMS)
        scales = scales.copyOf(cap)
        kinds = kinds.copyOf(cap)
        modalities = modalities.copyOf(cap)
        days = days.copyOf(cap)
        return this
    }

    internal fun append(chunkId: Long, fileId: Long, vector: ByteArray, scale: Float, kind: Kind, modality: Modality, day: Int) {
        chunkIds[size] = chunkId
        fileIds[size] = fileId
        vector.copyInto(bytes, size * DIMS)
        scales[size] = scale
        kinds[size] = kind.ordinal.toByte()
        modalities[size] = modality.ordinal.toByte()
        days[size] = day
        size++
    }

    companion object {
        fun empty() = withCapacity(0)

        fun withCapacity(n: Int) = VectorTable(LongArray(n), LongArray(n), ByteArray(n * DIMS), FloatArray(n), size = 0)
    }
}
