package app.retriever.core

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

    /** One file kept in two folders is one result that holds the other copy; a same-named file of another size stays its own. */
    @Test fun copiesInTwoFoldersAreOneResult() = runTest {
        fun pdf(folder: String, size: Long = 2_048) =
            SourceFile("content://test/$folder/lease.pdf", "lease.pdf", folder, "application/pdf", Kind.PDF, size, 1_700_000_000_123L)
        val (engine, _) = engine(newStore(), listOf(pdf("Download"), pdf("Documents"), pdf("Old", size = 99)), emptyList())
        val hits = engine.search("lease").hits
        assertEquals(2, hits.size, hits.map { it.file.folder }.toString())
        val folded = hits.single { it.file.size == 2_048L }
        assertEquals(setOf("Download", "Documents"), (listOf(folded) + folded.copies).map { it.file.folder }.toSet())
        assertTrue(hits.single { it.file.size == 99L }.copies.isEmpty())
    }

    @Test fun theLimitCannotDropTheExactFile() = runTest {
        val distractors = List(25) { testFile("notes-$it.txt") to "lease" }
        val (engine, ids) = engine(newStore(), listOf(testFile("lease.jpg", Kind.PHOTO)), distractors)
        val hits = engine.search("lease", limit = 20).hits
        assertEquals(20, hits.size)
        assertEquals(ids["lease.jpg"], hits.first().fileId)
    }

    @Test fun aFolderFullOfNewerFilesCannotCrowdOutTheExactName() {
        val inFolder = List(SearchEngine.CANDIDATES) { NameEntry(it + 1L, "scan-$it.pdf", "invoice", Kind.PDF, at = 2_000L) }
        val named = NameEntry(999L, "invoice.pdf", "Download", Kind.PDF, at = 1_000L)
        val hits = NameIndex.build(inFolder + named).search("invoice", limit = SearchEngine.CANDIDATES)
        assertEquals(NameHit(999L, MatchClass.EXACT, exactName = true), hits.first())
        assertEquals(SearchEngine.CANDIDATES, hits.size)
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
        val spy = SpyDriver(inner, onQuery = { sql, _ -> if ("chunk_fts MATCH" in sql) fts += sql })
        IndexDb.Schema.create(spy)
        val store = Store(spy).also { it.resetForModel(SIG) }
        val (engine, ids) = engine(store, listOf(testFile("lease.jpg", Kind.PHOTO)), listOf(testFile("notes.txt") to "lease"))
        assertTrue(ids["lease.jpg"] in engine.search("l").hits.map { it.fileId })
        assertEquals(emptyList(), fts)
        engine.search("le")
        assertTrue(fts.isNotEmpty(), "the spy sees the query once there are two letters")
    }
}
