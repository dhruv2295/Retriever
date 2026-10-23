package app.retriever.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class EtaTest {
    // Pixel #3: the photo rate was carried into the video phase.
    @Test
    fun remainingVideosCountAtTheVideoPace() {
        val book = SpeedBook().add(Kind.PHOTO, 1_000.0, 1.0).add(Kind.VIDEO, 10_000.0, 1e6)
        assertEquals(500_000.0, book.ms(mapOf(Kind.PHOTO to 100L, Kind.VIDEO to 40L), 40_000_000L), "500 s, not the 140 s the photo rate gave")
    }

    // Soak A1: an average of rates let one 50 ms doc outweigh the slow photos queued behind it.
    @Test
    fun oneFastDocThenSlowPhotosNeverReadsBelowTheTimeLeft() {
        var book = SpeedBook().add(Kind.DOC, 50.0, 1.0)
        for (read in 1..100) {
            book = book.add(Kind.PHOTO, 2_000.0, 1.0)
            val left = (100 - read) * 2_000.0
            assertTrue(book.ms(mapOf(Kind.PHOTO to 100L - read), 0) >= left, "after $read photos")
        }
    }

    @Test
    fun oneKindIsWithinTenPercentAfterTenFiles() {
        var book = SpeedBook()
        repeat(10) { book = book.add(Kind.PDF, if (it % 2 == 0) 3_000.0 else 5_000.0, 6.0) }
        val left = 390 * 4_000.0
        assertTrue(abs(book.ms(mapOf(Kind.PDF to 390L), 0) - left) <= 0.1 * left, "${book.ms(mapOf(Kind.PDF to 390L), 0)} vs $left")
    }

    @Test
    fun theBookOutlivesTheRetrieverAndIosCountsNightsAfterABackgroundRun() = runTest {
        val store = newStore().also { it.setProfile(PROFILE) }
        val ios = PIXEL.copy(platform = Platform.IOS)
        val files = listOf(testFile("a.txt"), testFile("b.txt"))
        val extractor = FakeExtractor(mapOf("a.txt" to listOf(textPiece("one")), "b.txt" to listOf(textPiece("two"))))
        val first = testRetriever(store, files, extractor, probe = ios)
        assertNull(first.estimateAll(), "nothing measured")
        first.indexBatch(1.minutes)
        assertEquals(2L, SpeedBook.decode(store.meta(Retriever.SPEED_KEY)).paces[Kind.DOC]?.files)

        val second = testRetriever(store, files, extractor, probe = ios)
        val estimate = assertNotNull(second.estimateAll(), "the stored book counts")
        assertNull(estimate.nightsOnCharger, "no background run yet")
        second.recordBackgroundRun(files = 400, seconds = 1_800)
        assertEquals(0, second.estimateAll()?.nightsOnCharger, "nothing left to read")
    }

    // Pixel #8: one metric line per file, with no name, path or uri.
    @Test
    fun successLogsOneMetricLineWithoutTheFileName() = runTest(StandardTestDispatcher()) {
        val logs = mutableListOf<String>()
        val store = newStore().also { it.setProfile(PROFILE) }
        val source = FakeSource().also {
            it.next = Changes(listOf(testFile("secret-name.txt")), emptyList(), "tok", isFullScan = true)
        }
        val r = Retriever(
            store, source, FakeExtractor(), FakeFactory(), FakeMonitor(),
            object : ContentHasher {
                override suspend fun quickHash(uri: String, size: Long): String? = null
                override suspend fun fullHash(uri: String): String? = null
            },
            DeviceProbe(Platform.ANDROID, 35, "Google", "Pixel", 8.0, false), { true }, { true },
            { m, _ -> logs += m }, System::currentTimeMillis, { 0L }, CoreDispatchers(StandardTestDispatcher(testScheduler)),
        )
        r.indexBatch(1.minutes)
        val metric = logs.filter { it.startsWith("Indexed ") }
        assertEquals(1, metric.size, "$logs")
        assertTrue("extract" in metric.single() && "embed" in metric.single() && "pieces" in metric.single())
        assertFalse(logs.any { "secret-name" in it || "content://" in it })
    }
}
