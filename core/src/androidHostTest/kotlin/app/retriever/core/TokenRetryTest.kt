package app.retriever.core

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.retriever.core.db.IndexDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TokenRetryTest {
    /** The term counts table is made on the first retry, not when the Store is built (that can be on the main thread). */
    @Test
    fun buildingAStoreRunsNoSqlTheFirstRetryMakesTheTermTable() {
        val db = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { IndexDb.Schema.create(it) }
        val executed = mutableListOf<String>()
        val spy = object : SqlDriver by db {
            override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
                executed += sql
                return db.execute(identifier, sql, parameters, binders)
            }
        }
        val store = Store(spy)
        assertTrue(executed.isEmpty(), "$executed")
        assertTrue(store.keywordSearch("zebra giraffe", 10).isEmpty())
        assertTrue(executed.any { "chunk_terms" in it })
    }

    private fun textOnlyStore(): Store {
        val store = newStore()
        val texts = mapOf(
            "lease.txt" to "lease agreement for the flat, security deposit",
            "a.txt" to "a note for my cat",
            "b.txt" to "something for my dog",
            "c.txt" to "reminder for my car service",
        )
        store.upsertFiles(texts.keys.map { testFile(it) })
        for ((name, text) in texts) {
            store.replaceChunks(store.fileByUri(testFile(name).uri)!!.id, listOf(Store.NewChunk(0, "", text, null)))
        }
        return store
    }

    private fun Store.fileNames(q: String) = keywordSearch(q, 10).map { file(chunk(it.chunkId)!!.fileId)!!.name }

    @Test
    fun droppingTheMostCommonWordsFindsTheLease() {
        val store = textOnlyStore()
        assertEquals(listOf("lease.txt"), store.fileNames("lease for my flat"))
        assertTrue(store.retries in 1..3, "retries ${store.retries}")
    }

    @Test
    fun anAndQueryWithHitsRunsNoRetry() {
        val store = textOnlyStore()
        assertEquals(3, store.fileNames("for my").size)
        assertEquals(0, store.retries)
    }

    @Test
    fun neverDropsBelowOneToken() {
        val store = textOnlyStore()
        assertTrue(store.fileNames("zebra giraffe okapi").isEmpty())
        assertEquals(2, store.retries)
        assertTrue(store.fileNames("zebra").isEmpty())
        assertEquals(2, store.retries)
    }

    /** "sea" is no word here, but "sea*" matches "seater": the fragment counts the words it starts, so it is not what is kept. */
    @Test
    fun aPrefixCountsTheWordsItStartsSoAFragmentIsNotKeptOverARarerWord() {
        val store = textOnlyStore()
        val invoice = testFile("invoice.txt")
        store.upsertFiles(listOf(invoice))
        store.replaceChunks(store.fileByUri(invoice.uri)!!.id, listOf(Store.NewChunk(0, "", "teak dining table, 6 seater, paid by bank transfer", null)))
        assertTrue(store.fileNames("sunset by the sea").isEmpty())
        // As the rarest word left, a prefix still finds what it starts.
        assertEquals(listOf("lease.txt"), store.fileNames("my depos"))
    }

    @Test
    fun nameLaneDropsTheMostFrequentWord() {
        val entries = listOf(
            NameEntry(1, "lease flat.pdf", "Documents", Kind.PDF),
            NameEntry(2, "notes.txt", "Documents", Kind.DOC),
            NameEntry(3, "todo.txt", "Documents", Kind.DOC),
        )
        val index = NameIndex.build(entries)
        assertEquals(listOf(1L), index.search("documents lease flat").map { it.id })
        assertTrue(index.search("qqqq xxxx").isEmpty())
    }
}
