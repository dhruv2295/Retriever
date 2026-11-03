package app.retriever.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** The vector scan's layout and kernels: blocks, parallel slices and the native dot product all find what one flat scan finds. */
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
    fun theNativeKernelScoresBitForBitAsDotInt8() {
        // Android and the JVM have none: their compilers vectorise dotInt8 itself.
        val kernel = dotRows ?: return
        val rows = 1_000
        val bytes = ByteArray(rows * DIMS).also { rnd.nextBytes(it) }
        // The extremes too: -128 never comes from Vector.of, but the sum must still match.
        bytes.fill(-128, 0, DIMS)
        bytes.fill(127, DIMS, 2 * DIMS)
        val scales = FloatArray(rows) { rnd.nextFloat() / 100 }
        val q = query()
        val out = FloatArray(rows)
        kernel.score(bytes, scales, rows, q.bytes, q.scale, out)
        assertContentEquals(
            List(rows) { dotInt8(bytes, it * DIMS, q.bytes, q.scale, scales[it]).toRawBits() },
            out.map { it.toRawBits() },
        )
    }

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

    /** A small library's lone first block is the one block an append copies: a published view keeps the old one. */
    @Test
    fun aViewKeepsTheFirstBlockWhileItGrows() {
        val all = flat(1_500)
        val table = VectorTable.empty()
        fun appendRows(rows: IntRange) {
            for (row in rows) {
                val vector = all.bytes.copyOfRange(row * DIMS, (row + 1) * DIMS)
                table.append(row + 1L, row + 1L, vector, all.scales[row], Kind.entries[all.kinds[row].toInt()], Modality.TEXT, all.days[row])
            }
        }
        appendRows(0 until 1_000)
        val q = query()
        val view = table.view()
        val first = view.block(0)
        val before = engine.scan(q, 100, view).pairs()

        appendRows(1_000 until all.size)

        assertNotSame(first, table.block(0), "the first block grew into a copy")
        assertSame(first, view.block(0))
        assertEquals(1_000, view.size)
        assertEquals(before, engine.scan(q, 100, view).pairs(), "a published view never sees the copy")
        assertEquals(all.top(q, 100), engine.scan(q, 100, table).pairs())
    }

    /** A search dropped mid-scan stops at the next block instead of scoring every row first. */
    @Test
    fun aCancelledScanStopsAtTheNextBlock() {
        val table = flat(VectorTable.BLOCK + 1).table()
        assertFailsWith<CancellationException> { engine.scan(query(), 100, table, job = Job().apply { cancel() }) }
    }

    @Test
    fun parallelSlicesFindWhatOneScanFinds() = runTest {
        val half = 3 * SearchEngine.SLICE_ROWS / 2
        val first = flat(half)
        // The second half repeats the first, so every cosine ties with a row in a later slice.
        val all = Flat(first.bytes + first.bytes, first.scales + first.scales, first.kinds + first.kinds, first.days + first.days)
        val table = all.table()
        val q = query()
        val allowed = BooleanArray(Kind.entries.size) { it != Kind.PHOTO.ordinal }
        val dead = BooleanArray(all.size) { it % 11 == 0 }
        val best = all.top(q, 100)
        withContext(Dispatchers.Default) {
            // An odd n cuts between the two rows of a tie: the earlier row stays, as one scan keeps it.
            for (n in listOf(1, 99, 100)) {
                val serial = engine.scan(q, n, table).pairs()
                assertEquals(best.take(n), serial)
                assertEquals(serial, engine.scanSliced(q, n, table, threads = 4).pairs())
                val counts = RelevanceRule().histogram()
                val slicedCounts = RelevanceRule().histogram()
                assertEquals(
                    engine.scan(q, n, table, allowed, histogram = counts, dead = dead).pairs(),
                    engine.scanSliced(q, n, table, allowed, histogram = slicedCounts, dead = dead, threads = 4).pairs(),
                )
                assertContentEquals(counts, slicedCounts)
            }
        }
    }
}
