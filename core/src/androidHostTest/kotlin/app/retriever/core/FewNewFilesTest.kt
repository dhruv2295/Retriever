package app.retriever.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** After the one-time catch-up, new files don't wait for the charger or for the phone to be put down. */
class FewNewFilesTest {
    private val onBatteryInUse = DeviceState(8.0, charging = false, batteryPct = 60, thermal = Thermal.NONE, screenOn = true)

    private fun test(body: suspend () -> Unit) = runBlocking { withTimeout(20.seconds) { body() } }

    @Test
    fun aFewNewFilesAreReadOnBatteryWhileThePhoneIsInUse() = test {
        val store = newStore()
        val files = (1..5).map { testFile("new$it.txt") }
        val r = testRetriever(store, files, FakeExtractor(), monitor = FakeMonitor(onBatteryInUse), fewWaitingLimit = ReadingPolicy.FEW_WAITING)
        r.refreshSetUp()
        r.indexBatch(1.minutes)
        assertEquals(5L, store.countsByState()[FileState.INDEXED] ?: 0L)
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
