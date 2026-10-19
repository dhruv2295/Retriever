package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnippetTest {
    @Test fun aMatchDeepInAnUnpunctuatedRunShows() {
        val text = "lorem ipsum ".repeat(25) + "the security deposit is due " + "dolor sit ".repeat(20)
        val snippet = SearchEngine.bestSentence(text, SearchEngine.folded("deposit"))
        assertTrue("deposit" in snippet, snippet)
        assertEquals(SearchEngine.SNIPPET, snippet.length)
    }

    @Test fun anEarlyMatchOrNoneKeepsTheStart() {
        val text = "We met on Tuesday. The security deposit is two months of rent. Keys on Friday."
        assertEquals("The security deposit is two months of rent.", SearchEngine.bestSentence(text, SearchEngine.folded("Security Déposit")))
        assertEquals(text.take(SearchEngine.SNIPPET), SearchEngine.bestSentence(text, SearchEngine.folded("guitar")))
    }

    @Test fun highlightingUsesWholeUnicodeWordsAndPrefixes() {
        val marked = SearchEngine.contentSnippet("al kcal calories calorie बिजली बिजलीघर अबिजली", SearchEngine.folded("al cal बिजली"))
        assertTrue("al kcal \u0002calories\u0003 \u0002calorie\u0003" in marked, marked)
        assertTrue("\u0002बिजली\u0003 \u0002बिजलीघर\u0003 अबिजली" in marked, marked)
    }

    @Test fun numericSpreadsheetWindowYieldsToTheNextTextMatch() {
        val text = "12345; ".repeat(12) + "deposit " + "12345; ".repeat(30) + "\nThe deposit covers the garden lease and is refundable when the tenant leaves."
        val snippet = SearchEngine.bestSentence(text, SearchEngine.folded("deposit"))
        assertEquals("The deposit covers the garden lease and is refundable when the tenant leaves.", snippet)
    }

    @Test fun clippingNeverTurnsTheEndOfAWordIntoAMatch() {
        val text = "x".repeat(17) + " kcal " + "x".repeat(36) + " target"
        val snippet = SearchEngine.contentSnippet(text, SearchEngine.folded("al target"))
        assertTrue("\u0002al\u0003" !in snippet, snippet)
        assertTrue("\u0002target\u0003" in snippet, snippet)
    }
}
