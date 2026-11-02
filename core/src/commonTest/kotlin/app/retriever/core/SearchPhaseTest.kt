package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchPhaseTest {
    private val q = Question("tax", null, null, starredOnly = false, literal = false)
    private val other = Question("rent", null, null, starredOnly = false, literal = false)
    private val none = SearchEngine.SearchResult(emptyList(), vectorLane = true)
    private val wordsOnlyNone = SearchEngine.SearchResult(emptyList(), vectorLane = false)
    private val some = SearchEngine.SearchResult(
        listOf(SearchEngine.Hit(SourceFile("u", "tax.pdf", "f", "application/pdf", Kind.PDF, 1, 1), 1, 0, "", null, 1.0)),
        vectorLane = true,
    )

    @Test fun newQuestionIsSearchingNeverEmpty() {
        val p = SearchPhase.Idle.onAsk(q)
        assertEquals(SearchPhase.Searching(q), p)
        assertFalse(p.showsEmpty(q))
    }

    @Test fun emptyNamesAnswerIsNotShown() {
        val p = SearchPhase.Idle.onAsk(q).onNames(q, none)
        assertEquals(SearchPhase.Searching(q), p)
        assertFalse(p.showsEmpty(q))
    }

    /** Typing on: an empty names answer leaves the earlier words' answer up, dimmed as stale, until the full one comes. */
    @Test fun emptyNamesAnswerKeepsTheEarlierAnswer() {
        val earlier = SearchPhase.Searching(q).onFull(q, some, willRetry = false)
        val next = q.copy(text = "taxes")
        val p = earlier.onNames(next, none)
        assertEquals(earlier, p)
        assertTrue(p.isStale(next))
        assertFalse(p.showsEmpty(next))
        assertEquals(SearchPhase.Searching(next), SearchPhase.Idle.onNames(next, none))
    }

    @Test fun aWordUnderThreeLettersIsStillBeingTyped() {
        assertTrue(midWord("b"))
        assertTrue(midWord("bill m"))
        assertFalse(midWord("bil"))
        assertFalse(midWord("bill "))
        assertFalse(midWord(""))
        assertFalse(midWord("账"))
    }

    @Test fun namesWithHitsShowAsIncomplete() {
        assertEquals(SearchPhase.Results(q, some, complete = false), SearchPhase.Searching(q).onNames(q, some))
    }

    @Test fun lateNamesNeverReplaceTheFullAnswer() {
        val full = SearchPhase.Searching(q).onFull(q, some, willRetry = false)
        assertEquals(full, full.onNames(q, some))
    }

    @Test fun wordsOnlyEmptyWithRetryPendingIsNotEmpty() {
        val p = SearchPhase.Searching(q).onFull(q, wordsOnlyNone, willRetry = true)
        assertEquals(SearchPhase.Results(q, wordsOnlyNone, complete = false), p)
        assertFalse(p.showsEmpty(q))
        assertTrue(SearchPhase.Searching(q).onFull(q, none, willRetry = false).showsEmpty(q))
    }

    @Test fun retryOfTheSameQuestionKeepsItsAnswer() {
        val done = SearchPhase.Searching(q).onFull(q, none, willRetry = false)
        assertEquals(done, done.onAsk(q))
        assertEquals(SearchPhase.Searching(other), done.onAsk(other))
    }

    @Test fun errorForSupersededQuestionIsIgnored() {
        val p = SearchPhase.Searching(other)
        assertEquals(p, p.onError(q))
        assertEquals(SearchPhase.Failed(q), SearchPhase.Searching(q).onError(q))
    }

    @Test fun failureThenNewTextSearches() {
        assertEquals(SearchPhase.Searching(other), SearchPhase.Failed(q).onAsk(other))
        assertEquals(SearchPhase.Searching(q), SearchPhase.Failed(q).onAsk(q))
    }

    @Test fun anyQuestionChangeMakesAnOldEmptyAnswerStale() {
        val empty = SearchPhase.Searching(q).onFull(q, none, willRetry = false)
        assertTrue(empty.showsEmpty(q))
        for (changed in listOf(
            q.copy(kind = Kind.PDF),
            q.copy(starredOnly = true),
            q.copy(literal = true),
            q.copy(similarTo = "photo-1"),
            q.copy(scope = listOf(1L)),
        )) {
            assertFalse(empty.showsEmpty(changed), "$changed")
            assertTrue(empty.isStale(changed), "$changed")
            assertNull(empty.answerFor(changed))
        }
        assertFalse(empty.isStale(q))
        assertFalse(SearchPhase.Idle.isStale(q))
        assertEquals(none, empty.answerFor(q))
    }
}
