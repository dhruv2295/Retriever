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
}
