package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewAskTest {
    private val day = 24L * 60 * 60 * 1000

    @Test fun asksOnTheThirdOpenThenWaitsFor120Days() {
        assertFalse(ReviewAsk.due(2, null, 0))
        assertTrue(ReviewAsk.due(3, null, 0))
        assertFalse(ReviewAsk.due(4, 0, 119 * day))
        assertTrue(ReviewAsk.due(5, 0, 120 * day))
    }
}
