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
        assertEquals(3, suggestions.size, "Try shows at most three")
        assertTrue(suggestions.any { ' ' in it }, suggestions.toString())
        assertFalse(suggestions.any { it.contains("telescope") || it.contains("nebula") })
        assertTrue(distinctiveTerms(files, setOf(1)).isEmpty(), "repeated chunks in one file do not count twice")
    }

    @Test fun longerRecurringWordsOutrankShortWordsAtEqualFrequency() {
        val terms = mapOf("oak" to 1, "orchard" to 1, "electricity" to 1)
        assertEquals(listOf("electricity", "orchard", "oak"), distinctiveTerms(mapOf(1L to terms, 2L to terms)))
    }

    /** The run's shape: a two-word phrase and each of its words were the top three; none may hold another. */
    @Test fun noSuggestionHoldsAnother() {
        val terms = mapOf("garden hose" to 3, "garden" to 3, "hose" to 3, "lantern" to 1, "meadow" to 1)
        val suggestions = distinctiveTerms(mapOf(1L to terms, 2L to terms))
        assertEquals(3, suggestions.size, suggestions.toString())
        for (a in suggestions) {
            for (b in suggestions - a) assertFalse(b.split(' ').containsAll(a.split(' ')), "$a is inside $b in $suggestions")
        }
    }

    @Test fun suggestionsComeFromDifferentKindsOfFileWhileThereAreAny() {
        val notes = mapOf("orchard" to 4, "garden" to 4, "harvest" to 4)
        val files = mapOf(1L to notes, 2L to notes, 3L to mapOf("boarding" to 1, "pebble" to 20), 4L to mapOf("boarding" to 1, "copper" to 20))
        val kinds = mapOf(1L to Kind.DOC, 2L to Kind.DOC, 3L to Kind.SCREENSHOT, 4L to Kind.SCREENSHOT)
        assertEquals(listOf("harvest", "orchard", "garden"), distinctiveTerms(files), "by score alone, all three are the notes'")
        assertEquals(listOf("harvest", "orchard", "boarding"), distinctiveTerms(files, kinds = kinds), "the screenshots' best beats a third note term")
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

    /** A long file counts by its opening, so one file's text cannot grow the maps without bound. */
    @Test fun onlyTheOpeningOfEachFileCounts() {
        val table = "4539,4000;".repeat(ContentSuggestions.TEXT / 10 + 1)
        val opening = ContentSuggestions().apply { refresh(mapOf(1L to 1L, 2L to 1L)) { listOf(prose, table) } }
        assertEquals(3, opening.suggestions(null).size)
        val buried = ContentSuggestions().apply { refresh(mapOf(1L to 1L, 2L to 1L)) { listOf(table, prose) } }
        assertTrue(buried.suggestions(null).isEmpty(), "the prose comes after the first ${ContentSuggestions.TEXT} characters")
    }

    /** However many files are read, suggestions sample the newest few, plus the ones a screen asks about. */
    @Test fun theStoreSamplesTheNewestReadFiles() {
        val store = Store(testDriver())
        val files = List(1_000) { SourceFile("file:///f$it", "f$it.txt", "", "text/plain", Kind.DOC, 1, modifiedAt = it.toLong()) }
        store.upsertFiles(files)
        val ids = store.inTransaction {
            files.map { f -> store.fileByUri(f.uri)!!.id.also { store.replaceChunks(it, listOf(Store.NewChunk(1, "", prose, null))) } }
        }
        val sample = store.readContentVersions(ContentSuggestions.FILES)
        assertEquals(ids.takeLast(ContentSuggestions.FILES).toSet(), sample.keys)
        assertEquals(sample.keys + ids[0], store.readContentVersions(ContentSuggestions.FILES, listOf(ids[0])).keys)
    }

    @Test fun storeExposesOnlyIndexedExtractedContent() {
        val store = Store(testDriver())
        val file = SourceFile("file:///one", "secret filename", "metadata folder", "text/plain", Kind.DOC, 1, 1)
        store.upsertFiles(listOf(file))
        val id = store.pending(1).single().id
        store.replaceChunks(id, listOf(Store.NewChunk(0, "", "synthetic metadata", null, prose)))
        assertEquals(listOf(prose), store.chunkTexts(id).mapNotNull { it.text })
        store.removeFiles(listOf(file.uri))
        assertTrue(store.readContentVersions(ContentSuggestions.FILES).isEmpty())
    }
}
