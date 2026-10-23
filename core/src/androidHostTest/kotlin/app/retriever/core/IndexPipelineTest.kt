package app.retriever.core

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.retriever.core.db.IndexDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.measureTime

class IndexPipelineTest {
    private fun engine(store: Store, extractor: Extractor, embedder: Embedder) = IndexEngine(store, extractor, embedder, FakeMonitor(), settingsOf())

    private suspend fun IndexEngine.batch(deepen: Boolean = false) = runBatch(9.minutes, requireCharging = false, deepen = deepen)

    private fun names(hits: List<SearchEngine.Hit>) = hits.map { it.file.name }

    @Test
    fun aPhotoCostsOneModelCallAndAFileWithNothingExtractedCostsOneNameCall() = runTest {
        val store = newStore()
        val embedder = FakeEmbedder()
        store.upsertFiles(listOf(testFile("cat.jpg", Kind.PHOTO, modifiedAt = 2), testFile("blob.bin", Kind.OTHER, modifiedAt = 1)))
        val extractor = FakeExtractor(mapOf("cat.jpg" to listOf(Piece("", listOf(Part.Image(ByteArray(10))), null))))

        engine(store, extractor, embedder).batch()

        assertEquals(2, embedder.received.size)
        assertTrue(embedder.received[0].single() is Part.Image, "the photo is embedded image-only, no name text")
        val nameCall = embedder.received[1].single() as Part.Text
        assertTrue(nameCall.text.startsWith("title: blob.bin | text: blob.bin"), nameCall.text)
        assertEquals(2L, store.chunkCount())
    }

    @Test
    fun aSyncedFileIsFoundByNameBeforeAnyEmbedding() = runTest {
        val store = newStore()
        val source = FakeSource().apply {
            next = Changes(listOf(testFile("Electricity Invoice.pdf", Kind.PDF, folder = "Download")), emptyList(), "t", isFullScan = true)
        }
        LibrarySync(store, source, now = { 0L }).sync()

        val hits = SearchEngine(store, FakeEmbedder()).search("invoice").hits
        assertEquals(listOf("Electricity Invoice.pdf"), names(hits))
        assertEquals(0L, hits.single().chunkId)
        assertEquals("", hits.single().locator)
        assertEquals(listOf("Electricity Invoice.pdf"), names(SearchEngine(store, FakeEmbedder()).search("download", SearchEngine.Mode.KEYWORD).hits))
    }

    @Test
    fun cheapKindsComeFirstThenPhotosThenVideosThenAudio() {
        val store = newStore()
        store.upsertFiles(listOf(testFile("new.jpg", Kind.PHOTO, modifiedAt = 5), testFile("old.png", Kind.SCREENSHOT, modifiedAt = 1)))
        assertEquals("old.png", store.pending(1).single().file.name)

        val mixed = newStore()
        mixed.upsertFiles(
            listOf(
                testFile("song.mp3", Kind.AUDIO, modifiedAt = 9),
                testFile("photo.jpg", Kind.PHOTO, modifiedAt = 1),
                testFile("clip.mp4", Kind.VIDEO, modifiedAt = 2),
            ),
        )
        // A video costs seconds of frames, so the older photo goes first.
        assertEquals(listOf("photo.jpg", "clip.mp4", "song.mp3"), mixed.pending(3).map { it.file.name })
    }

    @Test
    fun longDocumentsEmbedTheirHeadAndDeepenTheTailLater() = runTest {
        val store = newStore()
        val embedder = FakeEmbedder()
        val pieces = (0 until 20).map { textPiece(if (it == 15) "part$it zebra" else "part$it", "p. ${it + 1}") }
        store.upsertFiles(listOf(testFile("big.txt")))
        val search = SearchEngine(store, embedder)

        engine(store, FakeExtractor(mapOf("big.txt" to pieces)), embedder).batch()

        assertEquals(20L, store.chunkCount())
        assertEquals(20L - IndexEngine.DOC_HEAD_CHUNKS, store.tailChunkCount())
        assertEquals(IndexEngine.DOC_HEAD_CHUNKS, embedder.received.size)
        assertEquals(
            Store.Counts(
                mapOf(FileState.INDEXED to 1L),
                mapOf(Kind.DOC to 1L),
                20L - IndexEngine.DOC_HEAD_CHUNKS,
                1L,
                mapOf((Kind.DOC to FileState.INDEXED) to 1L),
                // No scan has run: the files were written directly.
                progress = ReadingProgress(partlyRead = 1, laterPasses = 1, counted = false),
                readByKind = mapOf(Kind.DOC to 1L),
                meaning = MeaningCounts(partlyAdded = 1),
            ),
            store.countsNow(),
        )
        val keyword = search.search("zebra", SearchEngine.Mode.KEYWORD).hits.single()
        assertEquals("p. 16", keyword.locator, "a tail chunk is keyword-searchable before it has a vector")
        val before = search.search("zebra", SearchEngine.Mode.VECTOR).hits.single()
        assertTrue(before.snippet?.contains("zebra") != true)

        val result = engine(store, FakeExtractor(), embedder).batch(deepen = true)

        assertEquals(StopReason.DONE, result.stopReason)
        assertEquals(0L, store.tailChunkCount())
        assertEquals(20L, store.chunkCount())
        assertEquals(1, store.keywordSearch("zebra", 10).size)
        val after = search.search("zebra", SearchEngine.Mode.VECTOR).hits.single()
        assertEquals("part15 \u0002zebra\u0003", after.snippet, "the loaded table picked up the deepened chunk")
        assertEquals("p. 16", after.locator)
    }

    @Test
    fun textGetsTheDocumentPrefixAndMediaGoesInRaw() = runTest {
        val store = newStore()
        val embedder = FakeEmbedder()
        val frame = Part.Image(ByteArray(33))
        val stamp = Part.Text("0:12")
        store.upsertFiles(listOf(testFile("notes.txt", modifiedAt = 2), testFile("clip.mp4", Kind.VIDEO, modifiedAt = 1)))
        val extractor = FakeExtractor(
            mapOf(
                "notes.txt" to listOf(textPiece("hello world")),
                "clip.mp4" to listOf(Piece("0:12", listOf(stamp, frame), "0:12")),
            ),
        )

        engine(store, extractor, embedder).batch()

        assertEquals(listOf<Part>(Part.Text("title: notes.txt | text: hello world")), embedder.received[0])
        val mixed = embedder.received[1]
        assertEquals(2, mixed.size)
        assertEquals(stamp, mixed[0])
        assertSame(frame, mixed[1])

        val search = SearchEngine(store, embedder)
        val text = search.search("hello")
        assertEquals(Part.Text("task: search result | query: hello"), embedder.received.last().single())
        assertEquals("notes.txt", text.hits.first().file.name)

        val jpeg = ByteArray(33)
        val image = search.searchImage(jpeg)
        val query = embedder.received.last().single() as Part.Image
        assertSame(jpeg, query.jpeg)
        assertTrue(image.vectorLane)
        assertEquals("clip.mp4", image.hits.first().file.name)
        assertEquals("0:12", image.hits.first().locator)
    }

    @Test
    fun namesMatchWithoutAccentsAndDevanagariWordsStayWhole() = runTest {
        val store = newStore()
        store.upsertFiles(listOf(testFile("Café Résumé.pdf", Kind.PDF), testFile("बिजली बिल.pdf", Kind.PDF)))
        val search = SearchEngine(store, FakeEmbedder())
        suspend fun find(q: String) = names(search.search(q, SearchEngine.Mode.KEYWORD).hits)

        assertEquals(listOf("Café Résumé.pdf"), find("cafe resume"))
        assertEquals(listOf("बिजली बिल.pdf"), find("बिल"))
        assertEquals(listOf("बिजली बिल.pdf"), find("बिज"))
        assertEquals(emptyList(), find("जल"), "जल (water) must not match inside बिजली (electricity)")
    }

    @Test
    fun theAttemptIsCountedBeforeTheFileIsTouched() = runTest {
        val store = newStore()
        store.upsertFiles(listOf(testFile("a.pdf", Kind.PDF)))
        val seen = mutableListOf<Int>()
        val extractor = FakeExtractor(onExtract = { f -> seen += store.fileByUri(f.uri)!!.attempts })

        engine(store, extractor, FakeEmbedder()).batch()

        assertEquals(listOf(1), seen)
    }

    @Test
    fun aFullDiskStopsTheBatchAndChargesNoFile() = runTest {
        val sqliteFull = SQLiteFullException("database or disk is full (code 13 SQLITE_FULL)")
        // A commit that finds the disk full, and an extraction whose temp file finds it full.
        for (full in listOf(sqliteFull, IOException("write failed: ENOSPC (No space left on device)"))) {
            val db = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            IndexDb.Schema.create(db)
            var armed = false
            val failing = object : SqlDriver by db {
                override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
                    if (armed && full === sqliteFull && "INSERT INTO chunks" in sql) throw full
                    return db.execute(identifier, sql, parameters, binders)
                }
            }
            val store = Store(failing).also { it.resetForModel(SIG) }
            val files = listOf(testFile("a.txt", modifiedAt = 2), testFile("b.txt", modifiedAt = 1))
            store.upsertFiles(files)
            armed = true
            val extractor = FakeExtractor(files.associate { it.name to listOf(textPiece("words")) }, onExtract = { if (full is IOException) throw full })

            assertEquals(BatchResult(0, 0, StopReason.DISK_FULL), engine(store, extractor, FakeEmbedder()).batch(), "$full")
            for (f in files) {
                val stored = store.fileByUri(f.uri)!!
                assertEquals(FileState.PENDING to 0, stored.state to stored.attempts, "${f.name} after $full")
            }
        }
    }

    /**
     * Indexes one file whose kind's switch [flip] turns mid-read. Returns the file's state, chunk count and attempts as
     * the first read left them; the batch then reads it again under the new setting.
     */
    private suspend fun midReadSwitch(start: Set<Kind>, flip: (Set<Kind>) -> Set<Kind>, inside: Boolean): Triple<FileState, Long, Int> {
        val store = newStore()
        val doc = testFile("a.txt")
        store.upsertFiles(listOf(doc))
        var kinds = start
        var first: Triple<FileState, Long, Int>? = null
        // A read inside flips the switch during extraction; a name-only read has no extraction, so it flips on the name's embed.
        val extractor = FakeExtractor(mapOf("a.txt" to listOf(textPiece("hello"))), onExtract = { if (inside) kinds = flip(kinds) })
        val embedder = FakeEmbedder { if (!inside && first == null) kinds = flip(kinds) }
        IndexEngine(
            store,
            extractor,
            embedder,
            FakeMonitor(),
            { IndexSettings(PROFILE, kinds) },
            onFile = { if (first == null) first = store.fileByUri(doc.uri)!!.let { Triple(it.state, store.chunkCount(), it.attempts) } },
        ).batch()
        assertEquals(FileState.INDEXED, store.fileByUri(doc.uri)!!.state, "the second read, under the new setting, commits")
        return first!!
    }

    @Test
    fun aKindSwitchedOffMidReadCommitsNothing() = runTest {
        assertEquals(Triple(FileState.PENDING, 0L, 0), midReadSwitch(Kind.entries.toSet(), { it - Kind.DOC }, inside = true))
    }

    @Test
    fun aKindSwitchedOnMidReadIsNotCommittedNameOnly() = runTest {
        assertEquals(Triple(FileState.PENDING, 0L, 0), midReadSwitch(Kind.entries.toSet() - Kind.DOC, { it + Kind.DOC }, inside = false))
    }

    @Test
    fun changedFileLosesOldWordsEvenIfReReadFails() = runTest {
        val store = newStore()
        val old = testFile("report.txt", modifiedAt = 1)
        store.upsertFiles(listOf(old))
        store.replaceChunks(store.fileByUri(old.uri)!!.id, listOf(Store.NewChunk(1, "", "oldsecret", null)))
        assertEquals(1, store.keywordSearch("oldsecret", 10).size)

        store.upsertFiles(listOf(old.copy(modifiedAt = 2)))
        assertEquals(0, store.keywordSearch("oldsecret", 10).size)

        IndexEngine(store, FakeExtractor(fail = true), null, FakeMonitor(), settingsOf()).batch()

        assertEquals(0, store.keywordSearch("oldsecret", 10).size)
        assertEquals(FileState.SKIPPED, store.fileByUri(old.uri)!!.state)
        assertEquals(1, store.fileKeywordSearch("report", 10).size, "still found by name")
    }

    @Test
    fun anIncrementalSyncRemovesFilesTheSourceNoLongerSees() = runTest {
        val store = newStore()
        val all = listOf(testFile("a"), testFile("b"), testFile("c"))
        val source = FakeSource().apply { next = Changes(all, emptyList(), "t1", isFullScan = true) }
        var clock = 0L
        val sync = LibrarySync(store, source, now = { clock })
        sync.sync()

        clock = 60_000
        source.next = Changes(emptyList(), emptyList(), "t2", present = setOf(all[0].uri, all[2].uri))
        val result = sync.sync()

        assertEquals("t1", source.calls.last())
        assertEquals(1, result.removed)
        assertNull(store.fileByUri(all[1].uri))
        assertEquals(2L, store.fileCount())
    }

    @Test
    fun concurrentFirstSearchesLoadTheVectorsOnce() = runTest {
        val store = newStore()
        val embedder = FakeEmbedder()
        store.upsertFiles((1..30).map { testFile("f$it.txt") })
        engine(store, FakeExtractor((1..30).associate { "f$it.txt" to listOf(textPiece("words for file $it")) }), embedder).batch()
        val search = SearchEngine(store, embedder)

        val results = withContext(Dispatchers.Default) {
            (1..8).map { async { search.search("file 7") } }.awaitAll()
        }

        assertEquals(1, search.fullLoads)
        assertTrue(results.all { it.vectorLane && it.hits.isNotEmpty() })
    }

    @Test
    fun scanOf200kRowsBenchmark() {
        val n = 200_000
        val rnd = Random(7)
        val bytes = ByteArray(n * DIMS).also { rnd.nextBytes(it) }
        val table = VectorTable(LongArray(n) { it + 1L }, LongArray(n) { it / 4L }, bytes, FloatArray(n) { 1f / 127 })
        val q = Vector.of(FloatArray(DIMS) { rnd.nextFloat() - 0.5f }, SIG)
        val search = SearchEngine(newStore(), FakeEmbedder())
        repeat(3) { search.scanTop(q, 100, table) }
        lateinit var top: List<Pair<Long, Float>>
        val took = measureTime { top = search.scanTop(q, 100, table) }
        println("scanTop over $n rows: $took")

        assertEquals(100, top.size)
        assertEquals(top.sortedByDescending { it.second }, top)
        val best = (0 until n).maxOf { dotInt8(bytes, it * DIMS, q.bytes, q.scale, 1f / 127) }
        assertEquals(best, top.first().second)
    }
}
