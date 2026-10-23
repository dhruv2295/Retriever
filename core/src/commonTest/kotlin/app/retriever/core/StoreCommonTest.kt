package app.retriever.core

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
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
    fun keywordSearchFoldsAccents() {
        val (store, id) = storeWith("receipt.txt", "Documents", "Café receipt total")
        val hit = store.keywordSearch("cafe", 10).single()
        assertEquals(id, store.chunk(hit.chunkId)?.fileId)
    }

    @Test
    fun devanagariMatchesWholeWordsNotSyllables() {
        val (store, _) = storeWith("bill.txt", "Documents", "बिजली का बिल")
        assertEquals(1, store.keywordSearch("बिजली", 10).size)
        assertTrue(store.keywordSearch("जल", 10).isEmpty())
    }

    /** CJK is written without spaces, so a word inside a run is found by its overlapping two-character pieces. */
    @Test
    fun aCjkWordInsideARunIsFound() {
        val (store, id) = storeWith("会議資料.txt", "書類", "会議の資料を送ります")
        assertEquals(id, store.chunk(store.keywordSearch("資料", 10).single().chunkId)?.fileId)
        assertEquals(1, store.keywordSearch("送ります", 10).size)
        assertEquals(1, store.keywordSearch("会議 資料", 10).size)
        assertEquals(1, store.keywordSearch("資", 10).size, "one character matches as a prefix")
        assertTrue(store.keywordSearch("資会", 10).isEmpty(), "the pieces must be adjacent")
        assertEquals(1, storeWith("notes.txt", "docs", "会議の資料report").first.keywordSearch("資料rep", 10).size, "a Latin tail is a prefix")
        assertEquals(listOf(id), store.fileKeywordSearch("資料", 10))
        assertEquals(listOf(id), store.fileKeywordSearch("書", 10))
        // A Korean particle joins the word before it.
        assertEquals(1, storeWith("bill.txt", "docs", "전기 고지서입니다").first.keywordSearch("고지서", 10).size)
    }

    /** An index written before CJK pieces is rewritten once, at the next refill check. */
    @Test
    fun cjkRowsWrittenWholeAreRewrittenOnce() {
        val driver = testDriver()
        val store = Store(driver)
        store.upsertFiles(listOf(SourceFile("folder://docs/会議資料.txt", "会議資料.txt", "docs", "text/plain", Kind.DOC, 10, 1)))
        val id = store.pending(1).single().id
        store.replaceChunks(id, listOf(Store.NewChunk(1, "", "会議の資料を送ります", null)))
        val chunk = store.keywordSearch("資料", 10).single().chunkId
        assertTrue(store.rebuildFtsIfPending(), "a new index records its tokenization")
        for (sql in listOf(
            "DELETE FROM chunk_fts",
            "INSERT INTO chunk_fts (rowid, text) VALUES ($chunk, '会議の資料を送ります')",
            "DELETE FROM file_fts",
            "INSERT INTO file_fts (rowid, name, folder) VALUES ($id, '会議資料.txt', 'docs')",
            "DELETE FROM index_meta WHERE key = 'fts_tokens'",
        )) {
            driver.execute(null, sql, 0)
        }
        assertTrue(store.keywordSearch("資料", 10).isEmpty())
        assertTrue(store.rebuildFtsIfPending())
        assertFalse(store.rebuildFtsIfPending(), "once")
        assertEquals(listOf(chunk), store.keywordSearch("資料", 10).map { it.chunkId })
        assertEquals(listOf(id), store.fileKeywordSearch("資料", 10))
    }

    /**
     * A later upgrade that recreates the keyword tables sets `fts_rebuild` on an index whose `fts_tokens` is current. The
     * refill copies text whole and commits; if the CJK pass after it dies, the next check must still run that pass.
     */
    @Test
    fun aRefillInterruptedBeforeItsCjkPassRunsThePassAgain() {
        var interrupt = false
        val raw = testDriver()
        val driver = object : SqlDriver by raw {
            override fun <R> executeQuery(
                identifier: Int?,
                sql: String,
                mapper: (SqlCursor) -> QueryResult<R>,
                parameters: Int,
                binders: (SqlPreparedStatement.() -> Unit)?,
            ): QueryResult<R> {
                // The CJK pass's first page: the process dies here.
                if (interrupt && sql.trimStart().startsWith("SELECT id, text, labels FROM chunks")) {
                    interrupt = false
                    error("killed")
                }
                return raw.executeQuery(identifier, sql, mapper, parameters, binders)
            }
        }
        val store = Store(driver)
        store.upsertFiles(listOf(SourceFile("folder://docs/会議資料.txt", "会議資料.txt", "docs", "text/plain", Kind.DOC, 10, 1)))
        val id = store.pending(1).single().id
        store.replaceChunks(id, listOf(Store.NewChunk(1, "", "会議の資料を送ります", null)))
        val chunk = store.keywordSearch("資料", 10).single().chunkId
        assertTrue(store.rebuildFtsIfPending(), "a new index records its tokenization")
        for (sql in listOf("DELETE FROM chunk_fts", "DELETE FROM file_fts", "INSERT INTO index_meta (key, value) VALUES ('fts_rebuild', '1')")) {
            driver.execute(null, sql, 0)
        }

        interrupt = true
        assertFails { store.rebuildFtsIfPending() }
        assertTrue(store.rebuildFtsIfPending(), "the pass runs again")
        assertFalse(store.rebuildFtsIfPending(), "once")
        assertEquals(listOf(chunk), store.keywordSearch("資料", 10).map { it.chunkId })
        assertEquals(listOf(id), store.fileKeywordSearch("資料", 10))
    }

    @Test
    fun fileKeywordSearchMatchesNameAndFolder() {
        val (store, id) = storeWith("lease agreement.pdf", "Rentals", "x")
        assertEquals(listOf(id), store.fileKeywordSearch("lease", 10))
        assertEquals(listOf(id), store.fileKeywordSearch("rentals", 10))
        assertTrue(store.fileKeywordSearch("invoice", 10).isEmpty())
    }
}
