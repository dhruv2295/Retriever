package app.retriever.core

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.measureTime

/**
 * Wall time per photo with and without reading ahead, on blocking stand-ins at a tenth of the Pixel 8 Pro's measured
 * costs: extraction 80 ms (0.8 s on the phone: decode, OCR, labels on CPU) and an image embed 48 ms (480 ms on its GPU).
 * Proves the overlap on one indexing thread, as production runs it; the phone's own gain needs the device run.
 */
class ReadAheadBenchTest {
    private class Gpu(private val ms: Long) : Embedder {
        override val signature = SIG
        override val backend = Backend.GPU

        override suspend fun embedRaw(parts: List<Part>): FloatArray {
            Thread.sleep(ms)
            return FloatArray(DIMS).also { it[0] = 1f }
        }

        override fun close() {}
    }

    private class SlowExtractor(private val ms: Long) : Extractor {
        override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> {
            Thread.sleep(ms)
            return listOf(Piece("", listOf(Part.Image(ByteArray(16))), null, labels = "sky"))
        }
    }

    private fun msPerFile(readAhead: Boolean, n: Int = 40): Double = Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { indexing ->
        val store = newStore().apply { upsertFiles(List(n) { testFile("IMG_$it.jpg", Kind.PHOTO, modifiedAt = n - it.toLong()) }) }
        val engine = IndexEngine(store, SlowExtractor(80), Gpu(48), FakeMonitor(), settingsOf(), readAheadDepth = { if (readAhead) 1 else 0 })
        runBlocking { withContext(indexing) { measureTime { engine.runBatch(10.minutes, requireCharging = false) } } }.inWholeMilliseconds.toDouble() / n
    }

    @Test
    fun readingAheadHidesTheEmbedBehindTheNextExtraction() {
        msPerFile(readAhead = false, n = 5)
        val serial = msPerFile(readAhead = false)
        val ahead = msPerFile(readAhead = true)
        println("ms per photo: serial ${"%.1f".format(serial)}, read ahead ${"%.1f".format(ahead)}")
        assertTrue(ahead < serial * 0.75, "read ahead $ahead ms vs serial $serial ms")
    }
}
