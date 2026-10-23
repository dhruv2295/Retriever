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

/**
 * Vectors for the scan, in blocks of [BLOCK] rows, each holding its rows in primitive arrays. An append fills the last
 * block, then adds a new one, so a full block is never copied: growth costs one block, not a second copy of the table
 * while a published [view] holds the first. Only a table's first block starts small and doubles up to [BLOCK], so a
 * small library holds little and a large one wastes at most its last block's free rows.
 */
class VectorTable private constructor(private var blocks: Array<Block>, size: Int) {
    /** The first [size] rows of these flat arrays, copied in; [bytes] holds [DIMS] per row. For tests and benches. */
    constructor(
        chunkIds: LongArray,
        fileIds: LongArray,
        bytes: ByteArray,
        scales: FloatArray,
        size: Int = chunkIds.size,
        /** [Kind] ordinal per row. */
        kinds: ByteArray = ByteArray(chunkIds.size),
        /** [Modality] ordinal per row. */
        modalities: ByteArray = ByteArray(chunkIds.size),
        /** Capture day per row: COALESCE(taken_at, modified_at) as whole UTC days since 1970. */
        days: IntArray = IntArray(chunkIds.size),
    ) : this(emptyArray(), 0) {
        ensureCapacity(size)
        for (row in 0 until size) {
            add(chunkIds[row], fileIds[row], bytes, row * DIMS, scales[row], kinds[row], modalities[row], days[row])
        }
    }

    /**
     * Rows b * [BLOCK] until (b + 1) * [BLOCK] of the table, for block b. The kind byte is apart from the modality byte,
     * since the scan indexes its kind filter by it; the day lets a date filter apply inside the scan, before the top-k cut.
     */
    internal class Block(capacity: Int) {
        val bytes = ByteArray(capacity * DIMS)
        val scales = FloatArray(capacity)
        val chunkIds = LongArray(capacity)
        val fileIds = LongArray(capacity)
        val kinds = ByteArray(capacity)
        val modalities = ByteArray(capacity)
        val days = IntArray(capacity)

        val capacity: Int get() = scales.size

        fun grown(capacity: Int) = Block(capacity).also {
            bytes.copyInto(it.bytes)
            scales.copyInto(it.scales)
            chunkIds.copyInto(it.chunkIds)
            fileIds.copyInto(it.fileIds)
            kinds.copyInto(it.kinds)
            modalities.copyInto(it.modalities)
            days.copyInto(it.days)
        }
    }

    var size = size
        private set

    val lastId: Long get() = if (size == 0) 0L else chunkId(size - 1)

    /** Rows the blocks have room for. */
    private val capacity: Int get() = blocks.lastOrNull()?.let { (blocks.size - 1) * BLOCK + it.capacity } ?: 0

    /** The blocks that hold rows; capacity reserved past [size] may add more that do not count. */
    internal val blockCount: Int get() = (size + BLOCK - 1) / BLOCK

    internal fun block(b: Int): Block = blocks[b]

    fun chunkId(row: Int): Long = blocks[row / BLOCK].chunkIds[row % BLOCK]

    fun fileId(row: Int): Long = blocks[row / BLOCK].fileIds[row % BLOCK]

    fun kind(row: Int): Kind = Kind.entries[blocks[row / BLOCK].kinds[row % BLOCK].toInt()]

    fun modality(row: Int): Modality = Modality.entries[blocks[row / BLOCK].modalities[row % BLOCK].toInt()]

    fun day(row: Int): Int = blocks[row / BLOCK].days[row % BLOCK]

    /**
     * The first [rows] rows, fixed: an append writes past them, and a new or grown block goes into a new [blocks] array,
     * so a scan of the view never races with appends here.
     */
    fun view(rows: Int = size): VectorTable {
        require(rows in 0..size)
        return VectorTable(blocks, rows)
    }

    /** Makes room for [n] rows. [blocks] is replaced, never changed in place, since [view]s share it. */
    internal fun ensureCapacity(n: Int): VectorTable {
        while (capacity < n) {
            val last = blocks.lastOrNull()
            blocks = when {
                last == null -> arrayOf(Block(minOf(BLOCK, maxOf(n, FIRST_BLOCK))))

                // Only a lone first block is short of BLOCK.
                last.capacity < BLOCK -> arrayOf(last.grown(minOf(BLOCK, maxOf(n, last.capacity * 2))))

                else -> blocks + Block(BLOCK)
            }
        }
        return this
    }

    internal fun append(chunkId: Long, fileId: Long, vector: ByteArray, scale: Float, kind: Kind, modality: Modality, day: Int) {
        ensureCapacity(size + 1)
        add(chunkId, fileId, vector, 0, scale, kind.ordinal.toByte(), modality.ordinal.toByte(), day)
    }

    private fun add(chunkId: Long, fileId: Long, vector: ByteArray, at: Int, scale: Float, kind: Byte, modality: Byte, day: Int) {
        val block = blocks[size / BLOCK]
        val i = size % BLOCK
        block.chunkIds[i] = chunkId
        block.fileIds[i] = fileId
        vector.copyInto(block.bytes, i * DIMS, at, at + DIMS)
        block.scales[i] = scale
        block.kinds[i] = kind
        block.modalities[i] = modality
        block.days[i] = day
        size++
    }

    companion object {
        /**
         * Rows per block: 4 MB of vectors, 4.6 MB in all. One row short of 4 MiB, so the vector array and its header fit
         * whole heap regions; at 16,384 rows the JVM's G1 rounds each up by a region (1 MB at a 512 MB heap).
         */
        internal const val BLOCK = 16_383
        private const val FIRST_BLOCK = 1_024

        fun empty() = VectorTable(emptyArray(), 0)

        fun withCapacity(n: Int) = empty().ensureCapacity(n)
    }
}
