package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** One chunk by id, through the batched [Store.chunks] production uses. */
internal fun Store.chunk(id: Long): Store.ChunkRow? = chunks(listOf(id))[id]

/** Keyword search on each platform's own SQLite: the JVM's bundled one and iOS's system library. */
class StoreCommonTest {
    private fun storeWith(name: String, folder: String, text: String): Pair<Store, Long> {
        val store = Store(testDriver())
        store.upsertFiles(listOf(SourceFile("folder://$folder/$name", name, folder, "text/plain", Kind.DOC, 10, 1)))
        val id = store.pending(1).single().id
        store.replaceChunks(id, listOf(Store.NewChunk(0, "", text, null)))
        return store to id
    }

    @Test
    fun keywordSearchFoldsAccentsAndMarksTheMatch() {
        val (store, id) = storeWith("receipt.txt", "Documents", "Café receipt total")
        val hit = store.keywordSearch("cafe", 10).single()
        assertEquals(id, store.chunk(hit.chunkId)?.fileId)
        assertTrue(hit.snippet.orEmpty().contains('\u0002'), hit.snippet)
    }

    @Test
    fun devanagariMatchesWholeWordsNotSyllables() {
        val (store, _) = storeWith("bill.txt", "Documents", "बिजली का बिल")
        assertEquals(1, store.keywordSearch("बिजली", 10).size)
        assertTrue(store.keywordSearch("जल", 10).isEmpty())
    }

    @Test
    fun fileKeywordSearchMatchesNameAndFolder() {
        val (store, id) = storeWith("lease agreement.pdf", "Rentals", "x")
        assertEquals(listOf(id), store.fileKeywordSearch("lease", 10))
        assertEquals(listOf(id), store.fileKeywordSearch("rentals", 10))
        assertTrue(store.fileKeywordSearch("invoice", 10).isEmpty())
    }
}
