package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentSuggestionsTest {
    @Test fun standardEnglishAndHindiStopwordsNumbersAndShortTokensAreDropped() {
        assertEquals(setOf("orchard", "बिजली", "बगीचा", "गुलाब"), frequencies(listOf("the and with है और का orchard बिजली बगीचा गुलाब 123 १२३ ab x9y")).keys)
    }

    @Test fun rareWordsOutrankWordsSharedByTheReadSet() {
        val files = (1L..10L).associateWith { frequencies(listOf("orchard cedar jasmine marigold")) }.toMutableMap()
        files[1] = frequencies(listOf("orchard cedar jasmine marigold telescope nebula"))
        assertEquals(listOf("nebula", "telescope"), distinctiveTerms(files, setOf(1)).take(2))
        assertTrue(distinctiveTerms(files, setOf(2)).all { it in files.getValue(2) })
        assertEquals(6, distinctiveTerms(files).size)
    }

    @Test fun tooLittleTextLeavesRoomForKindFilters() {
        assertTrue(distinctiveTerms(mapOf(1L to frequencies(listOf("orchard cedar")))).isEmpty())
        assertTrue(distinctiveTerms(emptyMap()).isEmpty())
        assertTrue(distinctiveTerms(mapOf(1L to frequencies(listOf("orchard cedar jasmine marigold"))), emptySet()).isEmpty())
    }

    @Test fun replacingOrDeletingFilesInvalidatesCachedTerms() {
        val cache = ContentSuggestions()
        var reads = 0
        cache.refresh(mapOf(1L to 1L)) {
            reads++
            listOf("orchard cedar jasmine marigold")
        }
        cache.refresh(mapOf(1L to 1L)) { error("unchanged content was read again") }
        assertEquals(1, reads)
        assertTrue("orchard" in cache.suggestions(null))
        cache.refresh(mapOf(1L to 2L)) { listOf("telescope nebula galaxy comet") }
        assertFalse("orchard" in cache.suggestions(null))
        cache.refresh(emptyMap()) { error("deleted content") }
        assertTrue(cache.suggestions(null).isEmpty())
    }

    @Test fun storeExposesOnlyIndexedExtractedContent() {
        val store = Store(testDriver())
        val file = SourceFile("file:///one", "secret filename", "metadata folder", "text/plain", Kind.DOC, 1, 1)
        store.upsertFiles(listOf(file))
        val id = store.pending(1).single().id
        store.replaceChunks(id, listOf(Store.NewChunk(0, "", "synthetic metadata", null, "orchard cedar jasmine marigold")))
        val cache = ContentSuggestions()
        cache.refresh(store.readContentVersions()) { key -> store.chunkTexts(key).mapNotNull { it.text } }
        assertEquals(setOf("orchard", "cedar", "jasmine", "marigold"), cache.suggestions(null).toSet())
        store.removeFiles(listOf(file.uri))
        assertTrue(store.readContentVersions().isEmpty())
    }
}
