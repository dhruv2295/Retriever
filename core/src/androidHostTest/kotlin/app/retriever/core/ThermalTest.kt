package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class ThermalTest {
    private fun state(t: Thermal, headroom: Float? = null, pluggedIn: Boolean = true, screenOn: Boolean = true) =
        DeviceState(8.0, charging = pluggedIn, 90, t, screenOn = screenOn, headroom = headroom)

    /** Reading pauses only at SEVERE, or when the forecast reaches the pause line; MODERATE and a high forecast read on. */
    @Test
    fun onlySevereHeatPauses() {
        assertFalse(state(Thermal.MODERATE).hot)
        assertFalse(state(Thermal.MODERATE, headroom = 0.9f).hot)
        assertTrue(listOf(Thermal.SEVERE, Thermal.CRITICAL).all { state(it).hot })
        assertTrue(state(Thermal.NONE, headroom = ThermalRest.PAUSE_AT).hot)
        assertTrue(state(Thermal.SEVERE, headroom = 0.5f).hot, "SEVERE pauses whatever the forecast")
    }

    @Test
    fun screenOffOnAChargerReadsOnUntilTheForecastReachesSevere() {
        assertFalse(state(Thermal.NONE, headroom = 0.97f, screenOn = false).hot)
        assertTrue(state(Thermal.NONE, headroom = 1f, screenOn = false).hot)
        assertTrue(state(Thermal.NONE, headroom = ThermalRest.PAUSE_AT, pluggedIn = false, screenOn = false).hot, "on battery")
    }

    @Test
    fun readAheadIsTwoOnlyHiddenOnACharger() {
        val off = state(Thermal.NONE, 0.2f, screenOn = false)
        assertEquals(2, IndexEngine.readAheadDepth(off, visible = false))
        assertEquals(2, IndexEngine.readAheadDepth(state(Thermal.MODERATE, 0.9f, screenOn = false), visible = false), "warmth doesn't narrow it")
        assertEquals(1, IndexEngine.readAheadDepth(off.copy(charging = false, pluggedIn = false), visible = false))
        assertEquals(1, IndexEngine.readAheadDepth(off.copy(ramGb = 4.0), visible = false), "under 6 GB: one file's pages at a time")
        assertEquals(2, IndexEngine.readAheadDepth(off.copy(ramGb = 6.0), visible = false))
        assertEquals(0, IndexEngine.readAheadDepth(off, visible = false, lowMemory = true), "the system says memory is low")
    }

    @Test
    fun inFrontReadAheadIsOneOnlyWhileUntouched() {
        assertEquals(0, IndexEngine.readAheadDepth(state(Thermal.NONE, 0.2f), visible = true), "in use: its frames come first")
        assertEquals(1, IndexEngine.readAheadDepth(state(Thermal.NONE, 0.2f), visible = true, idle = true), "untouched on a charger")
        assertEquals(0, IndexEngine.readAheadDepth(state(Thermal.NONE, 0.2f), visible = true, lowMemory = true, idle = true), "the system says memory is low")
    }

    @Test
    fun readAheadYieldsToAnotherAppEvenOnTheCharger() {
        for (plugged in listOf(false, true)) {
            val otherApp = state(Thermal.NONE, 0.2f, pluggedIn = plugged).copy(screenOn = true)
            assertEquals(0, IndexEngine.readAheadDepth(otherApp, visible = false))
            assertEquals(0, IndexEngine.readAheadDepth(otherApp, visible = false, idle = true))
        }
    }

    @Test
    fun theWorkManagerRecheckIsShortNotAFixedQuarterHour() {
        assertEquals(2.minutes, ThermalRest.RECHECK)
    }
}
