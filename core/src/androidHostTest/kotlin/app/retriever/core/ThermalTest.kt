package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class ThermalTest {
    private fun state(t: Thermal, headroom: Float? = null, pluggedIn: Boolean = true) = DeviceState(8.0, charging = pluggedIn, 90, t, headroom = headroom)

    @Test
    fun withoutAHeadroomModerateRestsAndLightResumes() {
        assertTrue(state(Thermal.MODERATE).hot)
        assertFalse(state(Thermal.LIGHT).hot)
        assertFalse(state(Thermal.NONE).hot)
        assertTrue(listOf(Thermal.MODERATE, Thermal.SEVERE, Thermal.CRITICAL).all { state(it).hot })
    }

    /** The headroom forecasts throttling, so reading goes on through MODERATE until the forecast nears SEVERE (1.0). */
    @Test
    fun theHeadroomDecidesTheRestWhereThePhoneHasOne() {
        assertFalse(state(Thermal.MODERATE, headroom = 0.9f).hot)
        assertTrue(state(Thermal.NONE, headroom = ThermalRest.PAUSE_AT).hot)
        assertTrue(state(Thermal.SEVERE, headroom = 0.5f).hot, "SEVERE rests whatever the forecast")
    }

    @Test
    fun readAheadNarrowsAsThePhoneWarms() {
        val table = listOf(
            state(Thermal.NONE, 0.5f) to 2,
            state(Thermal.NONE, 0.75f) to 1,
            state(Thermal.LIGHT, 0.5f) to 1,
            state(Thermal.NONE, 0.87f) to 0,
            state(Thermal.MODERATE, 0.6f) to 0,
            state(Thermal.NONE, 0.96f) to 0,
            state(Thermal.NONE) to 2,
            state(Thermal.LIGHT) to 1,
            state(Thermal.MODERATE) to 0,
        )
        for ((s, depth) in table) assertEquals(depth, IndexEngine.readAheadDepth(s, visible = false), "$s")
    }

    @Test
    fun readAheadIsTwoOnlyHiddenOnACharger() {
        assertEquals(2, IndexEngine.readAheadDepth(state(Thermal.NONE, 0.2f), visible = false))
        assertEquals(1, IndexEngine.readAheadDepth(state(Thermal.NONE, 0.2f, pluggedIn = false), visible = false))
        assertEquals(0, IndexEngine.readAheadDepth(state(Thermal.NONE, 0.2f), visible = true))
        assertEquals(1, IndexEngine.readAheadDepth(state(Thermal.NONE, 0.2f).copy(ramGb = 4.0), visible = false), "under 6 GB: one file's pages at a time")
        assertEquals(2, IndexEngine.readAheadDepth(state(Thermal.NONE, 0.2f).copy(ramGb = 6.0), visible = false))
    }

    @Test
    fun aRestEndsOnlyBelowWhereReadAheadStops() {
        assertFalse(state(Thermal.NONE, 0.9f).cooled, "under the pause line, but the next file would land back on it")
        assertTrue(state(Thermal.NONE, 0.8f).cooled)
        assertTrue(state(Thermal.LIGHT).cooled, "iOS fair")
        assertFalse(state(Thermal.SEVERE).cooled, "iOS serious")
    }

    @Test
    fun theWorkManagerRecheckIsShortNotAFixedQuarterHour() {
        assertEquals(2.minutes, ThermalRest.RECHECK)
    }
}
