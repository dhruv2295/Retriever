package app.retriever.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionBatteryTest {
    @Test fun explicitSessionsUseFiftyAndRepeatedBlockedResumeCannotBypassIt() = runBlocking {
        val store = newStore().also {
            it.setProfile(PROFILE)
            it.setMeta(LAST_FULL_SCAN_KEY, "1")
        }
        val file = testFile("waiting.txt")
        store.upsertFiles(listOf(file))
        val monitor = FakeMonitor(DeviceState(8.0, false, 80, Thermal.NONE))
        fun facade() = testRetriever(store, listOf(file), FakeExtractor(), monitor = monitor)
        val r = facade()
        r.refreshSetUp()
        r.setReadingSchedule(ReadingSchedule.CHARGING)
        r.startProcessing(false)
        assertEquals(50, ReadingPolicy.batteryFloor(r.prefs.value))
        monitor.state = monitor.state.copy(batteryPct = 49)
        assertEquals(StopReason.UNPLUGGED, r.readingBlocker())
        r.endReadNowSession()
        assertTrue(r.prefs.value.processingRequested, "host loss retains the request")
        monitor.state = monitor.state.copy(batteryPct = 74)
        r.startProcessing(false)
        val fresh = facade().apply { refreshSetUp() }
        assertEquals(50, ReadingPolicy.batteryFloor(fresh.prefs.value))
        monitor.state = monitor.state.copy(batteryPct = 50)
        assertEquals(null, fresh.readingBlocker())
        monitor.state = monitor.state.copy(batteryPct = 49)
        assertEquals(StopReason.UNPLUGGED, fresh.readingBlocker())
        val paused = fresh.readingStatus().first()
        assertEquals(Words.strings.processingPlugInDetail, paused.detail)
        assertFalse(paused.detail.orEmpty().contains("75"))
        fresh.endReadNowSession()
        repeat(2) {
            assertEquals(0L, fresh.startProcessing(false))
            assertFalse(fresh.prefs.value.started)
            assertTrue(fresh.prefs.value.processingRequested)
            assertEquals(StopReason.UNPLUGGED, fresh.readingBlocker())
        }
        monitor.state = monitor.state.copy(charging = true, pluggedIn = true)
        assertTrue(fresh.startProcessing(false) != 0L)
        assertEquals(50, ReadingPolicy.batteryFloor(fresh.prefs.value))
        fresh.pause()
        assertTrue(fresh.prefs.value.paused)
        assertFalse(fresh.prefs.value.processingRequested)
        assertFalse(fresh.prefs.value.started)
        assertEquals(75, ReadingPolicy.batteryFloor(fresh.prefs.value))
        assertEquals(ReadingSchedule.CHARGING, fresh.prefs.value.automaticReading)
    }
}
