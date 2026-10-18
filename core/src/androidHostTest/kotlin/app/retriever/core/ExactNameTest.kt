package app.retriever.core

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.retriever.core.db.IndexDb
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A file named exactly by the query comes first, above files that match it by words and meaning (F061, soak A4). */
class ExactNameTest {
    private val e = FakeEmbedder()

    /** Adds [named] (no text) and [bodies] (name to text, embedded); returns a warm engine and the ids by name. */
    private suspend fun engine(store: Store, named: List<SourceFile>, bodies: List<Pair<SourceFile, String>>): Pair<SearchEngine, Map<String, Long>> {
        store.upsertFiles(named + bodies.map { it.first })
        for ((f, text) in bodies) store.replaceChunks(store.fileByUri(f.uri)!!.id, listOf(Store.NewChunk(1, "", text, e.embed(listOf(Part.Text(text))))))
        val all = store.files(limit = Int.MAX_VALUE)
        val index = NameIndex.build(all.map { NameEntry(it.id, it.file.name, it.file.folder, it.file.kind, it.file.modifiedAt) })
        return SearchEngine(store, e, nameIndex = { index }) to all.associate { it.file.name to it.id }
    }

    @Test fun anExactNameBeatsWordsAndMeaning() = runTest {
        val (engine, ids) = engine(newStore(), listOf(testFile("lease.jpg", Kind.PHOTO)), listOf(testFile("notes.txt") to "lease"))
        assertEquals(listOf(ids["lease.jpg"], ids["notes.txt"]), engine.search("lease").hits.map { it.fileId })
    }

    @Test fun theLimitCannotDropTheExactFile() = runTest {
        val distractors = List(25) { testFile("notes-$it.txt") to "lease" }
        val (engine, ids) = engine(newStore(), listOf(testFile("lease.jpg", Kind.PHOTO)), distractors)
        val hits = engine.search("lease", limit = 20).hits
        assertEquals(20, hits.size)
        assertEquals(ids["lease.jpg"], hits.first().fileId)
    }

    @Test fun aFileNameQueryFindsThatFileFirst() = runTest {
        val (engine, ids) = engine(newStore(), listOf(testFile("IMG_1007.jpg", Kind.PHOTO)), listOf(testFile("scifact_1.txt") to "IMG_1007 was cited"))
        assertEquals(ids["IMG_1007.jpg"], engine.search("IMG_1007").hits.first().fileId)
    }

    @Test fun aFolderWordPinsNothing() = runTest {
        val shots = List(3) { testFile("IMG_$it.jpg", Kind.PHOTO, folder = "DCIM/Camera") }
        val (engine, ids) = engine(newStore(), shots, listOf(testFile("notes.txt") to "camera"))
        assertEquals(ids["notes.txt"], engine.search("camera").hits.first().fileId)
    }

    @Test fun oneLetterSkipsTheChunkKeywordQuery() = runTest {
        val fts = mutableListOf<String>()
        val inner = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val spy = object : SqlDriver by inner {
            override fun <R> executeQuery(
                identifier: Int?,
                sql: String,
                mapper: (SqlCursor) -> QueryResult<R>,
                parameters: Int,
                binders: (SqlPreparedStatement.() -> Unit)?,
            ): QueryResult<R> {
                if ("chunk_fts MATCH" in sql) fts += sql
                return inner.executeQuery(identifier, sql, mapper, parameters, binders)
            }
        }
        IndexDb.Schema.create(spy)
        val store = Store(spy).also { it.resetForModel(SIG) }
        val (engine, ids) = engine(store, listOf(testFile("lease.jpg", Kind.PHOTO)), listOf(testFile("notes.txt") to "lease"))
        assertTrue(ids["lease.jpg"] in engine.search("l").hits.map { it.fileId })
        assertEquals(emptyList(), fts)
        engine.search("le")
        assertTrue(fts.isNotEmpty(), "the spy sees the query once there are two letters")
    }
}
