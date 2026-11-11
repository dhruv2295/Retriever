package app.retriever.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Small backlogs keep names current while content processing follows the platform's resource policy. */
class FewNewFilesTest {
    private val onBatteryInUse = DeviceState(8.0, charging = false, batteryPct = 60, thermal = Thermal.NONE, screenOn = true)

    private fun test(body: suspend () -> Unit) = runBlocking { withTimeout(20.seconds) { body() } }

    @Test
    fun aFewNewFilesOnAndroidBypassTheReserveOnlyDownToFifty() = test {
        val cases = listOf(
            onBatteryInUse.copy(batteryPct = 80) to 5L,
            onBatteryInUse.copy(charging = true, pluggedIn = true, batteryPct = 80) to 5L,
            onBatteryInUse.copy(screenOn = false, batteryPct = 74) to 5L,
            onBatteryInUse.copy(batteryPct = 50) to 5L,
            onBatteryInUse.copy(batteryPct = 49) to 0L,
        )
        for ((device, processed) in cases) {
            val store = newStore()
            val files = (1..5).map { testFile("new$it.txt") }
            val extractor = FakeExtractor()
            val r = testRetriever(store, files, extractor, monitor = FakeMonitor(device), fewWaitingLimit = ReadingPolicy.FEW_WAITING)
            r.refreshSetUp()
            r.indexBatch(1.minutes)
            assertEquals(processed, store.countsByState()[FileState.INDEXED] ?: 0L, "$device")
            assertEquals(5L - processed, store.countsByState()[FileState.PENDING] ?: 0L, "$device")
            if (processed == 0L) assertTrue(extractor.names.isEmpty(), "blocked content processing never starts extraction")
            assertTrue(r.searchNames("new1").hits.isNotEmpty(), "name enumeration happens before the policy stop")
        }
    }

    @Test
    fun aBigBacklogStillWaitsForTheCharger() = test {
        val store = newStore()
        val files = (1..5).map { testFile("old$it.txt") }
        val r = testRetriever(store, files, FakeExtractor(), monitor = FakeMonitor(onBatteryInUse.copy(screenOn = false)), fewWaitingLimit = 4)
        r.refreshSetUp()
        assertEquals(NextRun.WHEN_CHARGING, r.indexBatch(1.minutes))
        assertEquals(0L, store.countsByState()[FileState.INDEXED] ?: 0L)
    }
}
