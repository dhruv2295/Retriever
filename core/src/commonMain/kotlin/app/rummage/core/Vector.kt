package app.rummage.core

import kotlin.jvm.JvmInline
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

const val DIMS = 256

/** Which model and settings produced a vector. Vectors with different signatures never mix. */
@JvmInline
value class Signature(val value: String) {
    companion object {
        fun of(model: ModelVariant, visionTokens: Int) = Signature("${model.name}/vt$visionTokens/d$DIMS")
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

/** Cosine between two raw model outputs, compared on their first [DIMS] dimensions. */
fun cosine(a: FloatArray, b: FloatArray): Float {
    val x = normalized(a.copyOf(DIMS))
    val y = normalized(b.copyOf(DIMS))
    var dot = 0f
    for (i in 0 until DIMS) dot += x[i] * y[i]
    return dot
}

/** Dot product of two int8 vectors; both are unit length, so this is their cosine. */
fun dotInt8(a: ByteArray, aOff: Int, b: ByteArray, bScale: Float, aScale: Float): Float {
    var acc = 0
    for (i in 0 until DIMS) acc += a[aOff + i] * b[i]
    return acc * aScale * bScale
}
