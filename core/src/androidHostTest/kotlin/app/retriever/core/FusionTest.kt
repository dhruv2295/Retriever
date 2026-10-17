package app.retriever.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class FusionTest {
    @Test
    fun aLiteralMatchAtEqualRankShowsTheWordsSnippet() = runTest {
        val store = newStore()
        val e = FakeEmbedder()
        val f = testFile("notes.pdf", Kind.PDF)
        store.upsertFiles(listOf(f))
        val text = "electricity bill paid in march"
        store.replaceChunks(store.fileByUri(f.uri)!!.id, listOf(Store.NewChunk(1, "", text, e.embed(listOf(Part.Text(text))))))
        val hit = SearchEngine(store, e).search("electricity bill").hits.single()
        // The words lane marks the matched terms; a meaning-lane snippet never does.
        assertTrue(hit.snippet.orEmpty().contains('\u0002'), hit.snippet)
    }
}
