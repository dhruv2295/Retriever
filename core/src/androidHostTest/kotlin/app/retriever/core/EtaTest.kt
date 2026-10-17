package app.retriever.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class EtaTest {
    // Pixel #3: the photo rate was carried into the video phase.
    @Test
    fun remainingVideosCountAtTheVideoPace() {
        val left = mapOf(Kind.PHOTO to 100L, Kind.VIDEO to 40L)
        val pace = mapOf(Kind.PHOTO to 1_000.0, Kind.VIDEO to 10_000.0)
        assertEquals(9, Retriever.etaMinutes(left, pace, 1_000.0), "500 s, not the 140 s the photo rate gave")
        assertEquals(3, Retriever.etaMinutes(left, mapOf(Kind.PHOTO to 1_000.0), 1_000.0))
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
            store, source, FakeExtractor(), FakeFactory(), FakeMonitor(), object : ContentHasher {
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
