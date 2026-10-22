package app.retriever.core

import app.retriever.core.android.HeadroomPoll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HeadroomPollTest {
    @Test
    fun theHeadroomIsReadAtMostOncePerPollAndNaNIsNone() {
        var now = 0L
        var reads = 0
        var value = 0.4f
        val poll = HeadroomPoll({ now }) {
            reads++
            value
        }
        assertEquals(0.4f, poll.get())
        value = 0.9f
        now = ThermalRest.POLL.inWholeMilliseconds - 1
        assertEquals(0.4f, poll.get(), "Android answers NaN to faster polling")
        assertEquals(1, reads)
        now = ThermalRest.POLL.inWholeMilliseconds
        assertEquals(0.9f, poll.get())
        value = Float.NaN
        now *= 2
        assertNull(poll.get(), "no thermal hardware: the status decides")
        assertEquals(3, reads)
    }
}
