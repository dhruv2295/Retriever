package app.retriever.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** The vector scan's block layout finds what one flat scan finds. */
class VectorScanTest {
    private val sig = Signature("M740/0123abcd/vt70/d256/t512")
    private val rnd = Random(5)
    private val engine = SearchEngine(Store(testDriver()), NoEmbedder(sig))

    private class NoEmbedder(override val signature: Signature) : Embedder {
        override val backend = Backend.CPU

        override suspend fun embedRaw(parts: List<Part>): FloatArray = error("scan only")

        override fun close() {}
    }

    /** Rows as wave 4 held them, one flat array per column, and the top [n] by brute force: cosine, then row. */
    private class Flat(val bytes: ByteArray, val scales: FloatArray, val kinds: ByteArray, val days: IntArray) {
        val size get() = scales.size

        fun table() = VectorTable(LongArray(size) { it + 1L }, LongArray(size) { it + 1L }, bytes, scales, size, kinds, ByteArray(size), days)

        fun top(q: Vector, n: Int, admit: (Int) -> Boolean = { true }): List<Pair<Int, Float>> = (0 until size).filter(admit)
            .map { it to dotInt8(bytes, it * DIMS, q.bytes, q.scale, scales[it]) }
            .sortedWith(compareByDescending<Pair<Int, Float>> { it.second }.thenBy { it.first })
            .take(n)
    }

    private fun flat(rows: Int) = Flat(
        ByteArray(rows * DIMS).also { rnd.nextBytes(it) },
        FloatArray(rows) { 0.002f + rnd.nextFloat() * 0.01f },
        ByteArray(rows) { rnd.nextInt(Kind.entries.size).toByte() },
        IntArray(rows) { rnd.nextInt(100) },
    )

    private fun query() = Vector(ByteArray(DIMS).also { rnd.nextBytes(it) }, 0.008f, sig)

    private fun SearchEngine.Scan.pairs() = rows.indices.map { rows[it] to cos[it] }

    @Test
    fun appendsLeaveEarlierBlocksAndViewsAsTheyWere() {
        val all = flat(3 * VectorTable.BLOCK + 100)
        val loaded = 2 * VectorTable.BLOCK + 5_000
        val table = Flat(all.bytes.copyOf(loaded * DIMS), all.scales.copyOf(loaded), all.kinds.copyOf(loaded), all.days.copyOf(loaded)).table()
        val q = query()
        val view = table.view()
        val blocks = List(table.blockCount) { table.block(it) }
        val before = engine.scan(q, 100, view).pairs()

        // As refresh appends by id: into the last block's free rows, then a new block.
        for (row in loaded until all.size) {
            val vector = all.bytes.copyOfRange(row * DIMS, (row + 1) * DIMS)
            table.append(row + 1L, row + 1L, vector, all.scales[row], Kind.entries[all.kinds[row].toInt()], Modality.TEXT, all.days[row])
        }

        assertEquals(4, table.blockCount)
        blocks.forEachIndexed { b, block -> assertSame(block, table.block(b), "block $b was copied") }
        assertEquals(loaded, view.size)
        assertEquals(before, engine.scan(q, 100, view).pairs(), "a published view never sees an append")
        assertEquals(all.top(q, 100), engine.scan(q, 100, table).pairs())
        val allowed = BooleanArray(Kind.entries.size) { it % 2 == 0 }
        val dead = BooleanArray(all.size) { it % 7 == 0 }
        assertEquals(
            all.top(q, 100) { it % 7 != 0 && it % 2 == 0 && all.kinds[it] % 2 == 0 && all.days[it] in 20..60 },
            engine.scan(q, 100, table, allowed, 20..60, LongArray(all.size / 2) { 2L * it + 1 }, dead = dead).pairs(),
        )
    }
}
