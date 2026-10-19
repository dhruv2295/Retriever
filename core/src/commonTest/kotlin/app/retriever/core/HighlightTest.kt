package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals

class HighlightTest {
    private fun marked(name: String, query: String) = nameMatchRanges(name, query).map { name.substring(it) }

    @Test fun wholeWordsMatch() {
        assertEquals(listOf("Tax", "Final"), marked("Tax return Final.pdf", "final tax"))
    }

    @Test fun lastTokenMatchesAsPrefixButEarlierOnesDoNot() {
        assertEquals(listOf("invoice", "report"), marked("invoice_report_2023", "invoice rep"))
        assertEquals(emptyList(), marked("invoices_report", "invoice report2"))
        assertEquals(listOf("invoices"), marked("invoices_report", "inv"))
    }

    @Test fun accentsAndCaseKeepTheOriginalSpelling() {
        assertEquals(listOf("Café"), marked("Café menu", "café"))
        assertEquals(listOf("Café"), marked("Café menu", "CAF"))
    }

    @Test fun oneOrTwoLetterPrefixMatchesNothing() {
        assertEquals(emptyList(), marked("invoice report", "in"))
        assertEquals(emptyList(), marked("invoice report", "i"))
        assertEquals(listOf("a"), marked("a plan", "a"))
    }

    @Test fun stopwordsAndShortWordsAreNotHighlightedInLongerQueries() {
        assertEquals(listOf("deposit"), marked("A deposit at the bank", "a deposit at the"))
        assertEquals(
            listOf("deposit"),
            wordMatchRanges("A deposit at the bank", listOf("a", "deposit", "at", "the")).map {
                "A deposit at the bank".substring(it)
            },
        )
        assertEquals("A \u0002deposit\u0003 at the bank", SearchEngine.contentSnippet("A deposit at the bank", listOf("a", "deposit", "at", "the")))
        assertEquals(listOf("at"), marked("A deposit at the bank", "at"))
        assertEquals(listOf("the"), marked("the bank", "the"))
        assertEquals(listOf("bank"), highlightTerms("the bank at"))
    }

    @Test fun emptyQuery() {
        assertEquals(emptyList(), marked("invoice", "  "))
    }
}
