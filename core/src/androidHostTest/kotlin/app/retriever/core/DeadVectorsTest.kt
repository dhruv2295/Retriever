package app.retriever.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.retriever.core.db.IndexDb
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class DeadVectorsTest {
    /** 100 deleted rows that match the query best must not fill the 100 candidate slots of a warm engine. */
    @Test fun deletedVectorsDoNotHideLiveOnes() = runTest {
        val store = newStore()
        val embedder = FakeEmbedder()
        val query = embedder.embed(listOf(Part.Text(Prefix.query("needle"))))
        val opposite = Vector.of(FloatArray(DIMS) { -query.bytes[it].toFloat() }, SIG)
        val dead = List(100) { testFile("dead-$it.txt") }
        val live = List(900) { testFile("live-$it.txt") }
        store.inTransaction {
            store.upsertFiles(dead + live)
            for (f in dead) store.replaceChunks(store.fileByUri(f.uri)!!.id, listOf(Store.NewChunk(1, "", null, query)))
            for (f in live) store.replaceChunks(store.fileByUri(f.uri)!!.id, listOf(Store.NewChunk(1, "", null, opposite)))
        }
        val deadIds = dead.map { store.fileByUri(it.uri)!!.id }.toSet()
        val search = SearchEngine(store, embedder)
        assertTrue(search.search("needle", SearchEngine.Mode.VECTOR).hits.isNotEmpty())
        store.removeFiles(dead.map { it.uri })
        repeat(2) {
            val hits = search.search("needle", SearchEngine.Mode.VECTOR).hits
            assertTrue(hits.isNotEmpty())
            assertTrue(hits.none { it.fileId in deadIds })
        }
        assertTrue(search.fullLoads <= 2)
    }

    /** A warm engine loaded the file under its modified day; a later taken_at backfill must move it to the capture day. */
    @Test fun takenAtBackfillMovesALoadedVectorToItsCaptureDay() = runTest {
        val day = 86_400_000L
        val taken = 600 * day
        val store = newStore()
        val embedder = FakeEmbedder()
        val file = testFile("beach.jpg", Kind.PHOTO, modifiedAt = 1_000 * day)
        store.upsertFiles(listOf(file))
        val id = store.fileByUri(file.uri)!!.id
        store.replaceChunks(id, listOf(Store.NewChunk(1, "", null, embedder.embed(listOf(Part.Text(Prefix.query("beach")))))))
        val search = SearchEngine(store, embedder)
        val onTakenDay = SearchRequest("beach", from = taken, to = taken + day, mode = SearchEngine.Mode.VECTOR)
        assertTrue(search.search(onTakenDay).hits.isEmpty(), "warm, and filed under the modified day")

        store.upsertFiles(listOf(file.copy(takenAt = taken)))
        assertEquals(listOf(id), search.search(onTakenDay).hits.map { it.fileId })
    }

    /** A refill that throws must not leave the name index empty, and keeps its flag so the next warm-up retries. */
    @Test fun aThrowingFtsRefillStillLeavesNamesLoaded() = runBlocking<Unit> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        IndexDb.Schema.create(driver)
        val store = Store(driver).also {
            it.resetForModel(Signature.of(ModelVariant.M740.generic, PROFILE.visionTokens, PROFILE.maxTokens))
            it.setProfile(PROFILE)
        }
        val source = FakeSource().apply { next = Changes(listOf(testFile("passport scan.txt")), emptyList(), "tok", isFullScan = true) }
        val r = Retriever(
            store, source, FakeExtractor(emptyMap()), FakeFactory(), FakeMonitor(), NoHasher, PIXEL, { true }, { true },
            { _, _ -> }, System::currentTimeMillis, { 0L },
        )
        r.refreshSetUp()
        r.indexBatch(1.minutes)
        store.setMeta("fts_rebuild", "1")
        driver.execute(null, "DROP TABLE chunk_fts", 0)

        assertFails { r.warmSearch() }
        assertEquals(listOf("passport scan.txt"), r.searchNames("pasport").hits.map { it.file.name })
        assertNotNull(store.meta("fts_rebuild"), "kept for the next warm-up")
    }

    private object NoHasher : ContentHasher {
        override suspend fun quickHash(uri: String, size: Long): String? = null
        override suspend fun fullHash(uri: String): String? = null
    }
}
