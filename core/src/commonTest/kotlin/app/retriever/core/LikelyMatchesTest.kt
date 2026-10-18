package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals

class LikelyMatchesTest {
    @Test fun aClearDropCollapsesOnlyTheTail() {
        assertEquals(3, likelyCount(listOf(0.91, 0.89, 0.88, 0.40, 0.39, 0.38)))
        assertEquals(4, likelyCount(listOf(0.91, 0.89, 0.88, 0.88, 0.40, 0.39)))
    }

    @Test fun theSplitIsRelativeToEachLanesScores() {
        val scores = listOf(0.9, 0.89, 0.88, 0.2, 0.19)
        assertEquals(likelyCount(scores), likelyCount(scores.map { it * 0.01 - 2 }))
    }

    @Test fun flatOrShortListsStayVisible() {
        assertEquals(0, likelyCount(emptyList()))
        assertEquals(1, likelyCount(listOf(-1.0)))
        assertEquals(3, likelyCount(listOf(0.9, 0.2, 0.1)))
        assertEquals(5, likelyCount(List(5) { 0.1 }))
        assertEquals(5, likelyCount(listOf(5.0, 4.0, 3.0, 2.0, 1.0)))
    }
}
