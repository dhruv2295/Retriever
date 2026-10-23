package app.retriever.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Date and starred scope apply before each lane's 100-hit cap, not after it. */
class ScopeBeforeCapTest {
    @Test fun nameLanesScopeBeforeTheCap() {
        val store = newStore()
        val recent = List(100) { testFile("invoice-$it.txt", modifiedAt = 2_000_000) }
        val old = testFile("invoice-old.txt", modifiedAt = 1_000_000)
        store.upsertFiles(recent + old)
        val oldId = store.fileByUri(old.uri)!!.id
        val index = NameIndex.build(
            store.files(limit = Int.MAX_VALUE).map { NameEntry(it.id, it.file.name, it.file.folder, it.file.kind, it.file.takenAt ?: it.file.modifiedAt) },
        )
        val byDate = SearchRequest("invoice", from = 900_000, to = 1_100_000)
        val byStar = SearchRequest("invoice", fileIds = setOf(oldId))
        for ((name, engine) in listOf("warm" to SearchEngine(store, FakeEmbedder(), nameIndex = { index }), "cold" to SearchEngine(store, FakeEmbedder()))) {
            assertEquals(oldId, engine.names(byDate).hits.single().fileId, "$name by date")
            assertEquals(oldId, engine.names(byStar).hits.single().fileId, "$name by star")
        }
    }

    @Test fun keywordLaneScopesStarredBeforeTheCap() = runTest {
        val store = newStore()
        val old = testFile("doc-old.txt", modifiedAt = 1_000_000)
        val recent = List(100) { testFile("doc-$it.txt", modifiedAt = 2_000_000) }
        store.upsertFiles(recent + old)
        for (f in recent + old) store.replaceChunks(store.fileByUri(f.uri)!!.id, listOf(Store.NewChunk(1, "", "invoice", null)))
        store.setStarred(old.uri, true, at = 1)
        val oldId = store.fileByUri(old.uri)!!.id
        val hits = SearchEngine(store, FakeEmbedder())
            .search(SearchRequest("invoice", fileIds = setOf(oldId), onlyStarred = true, mode = SearchEngine.Mode.KEYWORD)).hits
        assertEquals(oldId, hits.single().fileId)
    }

    @Test fun coldNameLaneScopesStarredBeforeItsFtsCap() {
        val store = newStore()
        val recent = List(Store.FTS_CAP.toInt() + 1) { testFile("invoice-$it.txt", modifiedAt = 2_000_000) }
        val old = testFile("invoice-old.txt", modifiedAt = 1_000_000)
        // Indexed first, so it is the oldest row the keyword table holds.
        store.upsertFiles(listOf(old) + recent)
        store.setStarred(old.uri, true, at = 1)
        val oldId = store.fileByUri(old.uri)!!.id
        val hits = SearchEngine(store, FakeEmbedder()).names(SearchRequest("invoice", fileIds = setOf(oldId), onlyStarred = true)).hits
        assertEquals(oldId, hits.single().fileId)
    }

    @Test fun keywordLaneScopesContentBeforeTheCap() = runTest {
        val store = newStore()
        val target = testFile("notes.txt", modifiedAt = 1_000_000)
        val outside = List(150) { testFile("doc-$it.txt", modifiedAt = 2_000_000) }
        store.upsertFiles(outside + target)
        // The short chunks outside the scope rank above the target's long one.
        for (f in outside) store.replaceChunks(store.fileByUri(f.uri)!!.id, listOf(Store.NewChunk(1, "", "invoice", null)))
        val targetId = store.fileByUri(target.uri)!!.id
        val long = "invoice " + List(60) { "filler$it" }.joinToString(" ")
        store.replaceChunks(targetId, listOf(Store.NewChunk(1, "", long, null)))
        val hits = SearchEngine(store, FakeEmbedder())
            .search(SearchRequest("invoice", contentIds = setOf(targetId), mode = SearchEngine.Mode.KEYWORD)).hits
        assertEquals(listOf(targetId), hits.map { it.fileId })
    }
}
