package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentSuggestionsTest {
    private val prose = "The security deposit protects the orchard. Electricity receipts describe garden maintenance."

    @Test fun structuredSourcesNeverSupplySuggestions() {
        val mixed = listOf(
            prose,
            "{\"success\":true,\"axisb\":false,\"api\":null,\"codes\":[123,456]}",
            "bank,code,amount\nAXISB,A12,4539\nAXISB,B32,4000",
            "fun getValue(userId) { return api.set(userId, false); }",
        )
        val terms = frequencies(mixed)
        assertTrue("security deposit" in terms)
        assertFalse(terms.keys.any { it.contains("axisb") || it.contains("success") || it.contains("api") || it.contains("false") })
        for (mime in listOf("text/csv", "application/json", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/x-python")) {
            assertTrue(frequencies(listOf(prose), mime).isEmpty(), mime)
        }
    }

    @Test fun stopwordsIdentifiersNumbersAndShortCodesAreDroppedBeforeFoldingCase() {
        val terms = frequencies(listOf("The orchard holds AXISB and A12. true false null api set get id success codes. है और का बिजली बगीचा गुलाब."))
        assertTrue(setOf("orchard", "बिजली", "बगीचा", "गुलाब").all { it in terms })
        assertFalse(terms.keys.any { it in setOf("axisb", "a12", "true", "false", "api", "set", "get", "success", "codes") })
    }

    @Test fun termsAndPhrasesNeedTwoDistinctFilesInTheSelectedRead() {
        val files = mapOf(1L to frequencies(listOf(prose, prose, "Unique telescope nebula.")), 2L to frequencies(listOf(prose)))
        val suggestions = distinctiveTerms(files)
        assertTrue(suggestions.size >= 3)
        assertTrue("security deposit" in suggestions, suggestions.toString())
        assertFalse(suggestions.any { it.contains("telescope") || it.contains("nebula") })
        assertTrue(distinctiveTerms(files, setOf(1)).isEmpty(), "repeated chunks in one file do not count twice")
    }

    @Test fun longerRecurringWordsOutrankShortWordsAtEqualFrequency() {
        val terms = mapOf("oak" to 1, "orchard" to 1, "electricity" to 1)
        assertEquals(listOf("electricity", "orchard", "oak"), distinctiveTerms(mapOf(1L to terms, 2L to terms)))
    }

    @Test fun fewerThanThreeGoodTermsFallsBackToKinds() {
        assertTrue(distinctiveTerms(mapOf(1L to mapOf("orchard" to 1), 2L to mapOf("orchard" to 1))).isEmpty())
        assertTrue(distinctiveTerms(emptyMap()).isEmpty())
    }

    @Test fun replacingOrDeletingFilesInvalidatesCachedTerms() {
        val cache = ContentSuggestions()
        var reads = 0
        cache.refresh(mapOf(1L to 1L, 2L to 2L)) {
            reads++
            listOf(prose)
        }
        cache.refresh(mapOf(1L to 1L, 2L to 2L)) { error("unchanged content was read again") }
        assertEquals(2, reads)
        assertTrue(cache.suggestions(null).isNotEmpty())
        cache.refresh(mapOf(1L to 3L, 2L to 2L)) { listOf("Telescope nebula galaxy comet.") }
        assertTrue(cache.suggestions(null).isEmpty(), "old prose no longer occurs in two files")
        cache.refresh(emptyMap()) { error("deleted content") }
        assertTrue(cache.suggestions(null).isEmpty())
    }

    @Test fun storeExposesOnlyIndexedExtractedContent() {
        val store = Store(testDriver())
        val file = SourceFile("file:///one", "secret filename", "metadata folder", "text/plain", Kind.DOC, 1, 1)
        store.upsertFiles(listOf(file))
        val id = store.pending(1).single().id
        store.replaceChunks(id, listOf(Store.NewChunk(0, "", "synthetic metadata", null, prose)))
        assertEquals(listOf(prose), store.chunkTexts(id).mapNotNull { it.text })
        store.removeFiles(listOf(file.uri))
        assertTrue(store.readContentVersions().isEmpty())
    }
}
