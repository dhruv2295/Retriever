package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class ThermalTest {
    private fun state(t: Thermal) = DeviceState(8.0, charging = true, 90, t)

    @Test
    fun moderateRestsAndLightResumesOnTheNextCheck() {
        assertTrue(state(Thermal.MODERATE).hot)
        assertFalse(state(Thermal.LIGHT).hot)
        assertFalse(state(Thermal.NONE).hot)
    }

    @Test
    fun moderateStaysRestingAcrossChecks() {
        val checks = listOf(Thermal.MODERATE, Thermal.MODERATE, Thermal.SEVERE)
        assertTrue(checks.all { state(it).hot })
    }

    @Test
    fun theRecheckIsShortNotAFixedQuarterHour() {
        assertEquals(2.minutes, ThermalRest.RECHECK)
    }
}
