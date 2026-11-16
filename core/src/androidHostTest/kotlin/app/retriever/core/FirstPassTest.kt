package app.retriever.core

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.retriever.core.db.IndexDb
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** What the first pass embeds, and what it leaves to the deepen pass, so the first pass is fast and nothing is lost. */
class FirstPassTest {
    private fun vectorLocators(store: Store): List<String> {
        val table = store.loadVectors()
        val ids = (0 until table.size).map { table.chunkId(it) }
        return store.chunks(ids).values.sortedBy { it.locator }.map { it.locator }
    }

    private fun engine(store: Store, extractor: Extractor, embedder: Embedder = FakeEmbedder()) =
        IndexEngine(store, extractor, embedder, FakeMonitor(), settingsOf())

    private fun rowStore(): Pair<SqlDriver, Store> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { IndexDb.Schema.create(it) }
        return driver to Store(driver).also { it.resetForModel(SIG) }
    }

    /** Every chunk as stored, with its keyword row; ordinals aside, which a placeholder's read takes from its place. */
    private fun rows(driver: SqlDriver): List<String> = driver.executeQuery(
        null,
        """SELECT c.ordinal = 0, c.locator, c.text, c.content_text, c.labels, c.visual, c.content, c.vector IS NOT NULL, f.text, f.labels
           FROM chunks c LEFT JOIN chunk_fts f ON f.rowid = c.id ORDER BY c.visual DESC, c.text""",
        { c ->
            val rows = mutableListOf<String>()
            while (c.next().value) rows += (0 until 10).joinToString("|") { c.getString(it).toString() }
            QueryResult.Value(rows)
        },
        0,
    ).value

    @Test
    fun aShortOcrScrapIsFoundByKeywordWithoutAVectorOfItsOwn() = runTest {
        val store = newStore()
        val photo = testFile("door.jpg", Kind.PHOTO)
        store.upsertFiles(listOf(photo))
        val embedder = FakeEmbedder()
        val extractor = FakeExtractor(mapOf(photo.name to imageWithText("", ByteArray(3), "4471", 800)))

        engine(store, extractor, embedder).runBatch(1.minutes, requireCharging = false)

        assertEquals(1, store.vectorCount(), "the picture only")
        assertTrue(embedder.received.single().all { it is Part.Image }, "the scrap is not model input")
        assertEquals("door.jpg", SearchEngine(store, FakeEmbedder()).search("4471", SearchEngine.Mode.KEYWORD).hits.single().file.name)
        assertEquals(Modality.IMAGE, store.loadVectors().modality(0), "words on a picture leave it a picture")
    }

    @Test
    fun aDocumentEmbedsItsOpeningAndAScreenshotMoreOfItsOcr() = runTest {
        val store = newStore()
        val doc = testFile("lease.docx", Kind.DOC)
        val shot = testFile("chat.png", Kind.SCREENSHOT)
        store.upsertFiles(listOf(doc, shot))
        val parts = (1..10).map { textPiece("part $it of a long text") }
        engine(store, FakeExtractor(mapOf(doc.name to parts, shot.name to parts))).runBatch(1.minutes, requireCharging = false)

        assertEquals(2, IndexEngine.DOC_HEAD_CHUNKS)
        assertEquals((IndexEngine.DOC_HEAD_CHUNKS + IndexEngine.HEAD_TEXT_CHUNKS).toLong(), store.vectorCount().toLong())
        assertEquals((10 - IndexEngine.DOC_HEAD_CHUNKS + 10 - IndexEngine.HEAD_TEXT_CHUNKS).toLong(), store.tailChunkCount())
    }

    /** A video's first pass embeds one moment; the others are placeholders the deepen pass reads and embeds. */
    @Test
    fun aVideoEmbedsOneMomentFirstAndTheRestInTheDeepenPass() = runTest {
        val store = newStore()
        val clip = testFile("clip.mp4", Kind.VIDEO)
        store.upsertFiles(listOf(clip))
        val frames = mutableListOf<String>()
        val extractor = object : Extractor {
            override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> = error("the model is here: the head is read")

            override suspend fun extractHead(file: SourceFile, profile: DeviceProfile) =
                listOf(later("0:05"), Piece("0:15", listOf(Part.Image(ByteArray(15))), null), later("0:25"))

            override suspend fun visual(file: SourceFile, locator: String): Part.Image {
                frames += locator
                return Part.Image(ByteArray(locator.takeLast(2).toInt()))
            }
        }
        val id = { store.fileByUri(clip.uri)!!.id }

        engine(store, extractor).runBatch(1.minutes, requireCharging = false)
        assertEquals(listOf("0:15"), vectorLocators(store))
        assertEquals(2L, store.tailChunkCount())
        assertEquals(mapOf(id() to ReadExtent.DEEPENING), store.readExtents(listOf(id())))
        store.countsNow().progress.let { assertEquals(1L to 1L, it.partlyRead to it.laterPasses, "processed in part, more to come") }

        engine(store, extractor).runBatch(1.minutes, requireCharging = false, deepen = true)
        assertEquals(listOf("0:05", "0:25"), frames)
        assertEquals(listOf("0:05", "0:15", "0:25"), vectorLocators(store))
        assertEquals(0L, store.tailChunkCount())
        assertEquals(emptyMap(), store.readExtents(listOf(id())))
        assertEquals(1L, store.countsNow().progress.read)
    }

    /** A scanned PDF's later pages are rendered and OCR'd in the deepen pass: their words become searchable then. */
    @Test
    fun aScannedPdfReadsItsLaterPagesInTheDeepenPass() = runTest {
        val store = newStore()
        val pdf = testFile("scan.pdf", Kind.PDF)
        store.upsertFiles(listOf(pdf))
        val ocrd = mutableListOf<String>()
        val words = listOf("apple", "banana", "cherry", "damson", "elderberry")
        fun page(n: Int) = imageWithText(Locator.page(n), ByteArray(n), "page $n of the signed lease, clause ${n * 7}, ${words[n - 1]}", 800)
        val extractor = object : Extractor {
            override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> = error("the model is here: the head is read")

            override suspend fun extractHead(file: SourceFile, profile: DeviceProfile) =
                pdfPieces(5, false, { "" }, { i, _ -> page(i + 1).also { ocrd += Locator.page(i + 1) } }, 800, PDF_HEAD_OCR_PAGES)
                    .also { assertEquals(PDF_HEAD_OCR_PAGES, ocrd.size) }

            override suspend fun later(file: SourceFile, locator: String, profile: DeviceProfile): List<Piece> {
                ocrd += locator
                return page(Locator.pageOf(locator)!!)
            }
        }
        val search = SearchEngine(store, FakeEmbedder())

        engine(store, extractor).runBatch(1.minutes, requireCharging = false)
        assertTrue(search.search("cherry", SearchEngine.Mode.KEYWORD).hits.isNotEmpty())
        assertTrue(search.search("elderberry", SearchEngine.Mode.KEYWORD).hits.isEmpty(), "page 5 waits for the deepen pass")

        engine(store, extractor).runBatch(1.minutes, requireCharging = false, deepen = true)
        assertEquals((1..5).map { Locator.page(it) }, ocrd, "each page is read once")
        assertEquals(Locator.page(5), search.search("elderberry", SearchEngine.Mode.KEYWORD).hits.single().locator)
        assertEquals(0L, store.tailChunkCount())
        // Every page's picture and words have vectors, as a whole first pass would have given them.
        assertEquals(10, store.vectorCount())
    }

    @Test
    fun withoutTheModelAScannedPdfIsReadWhole() = runTest {
        val store = newStore(sig = null)
        val pdf = testFile("scan.pdf", Kind.PDF)
        store.upsertFiles(listOf(pdf))
        var heads = 0
        val extractor = object : Extractor {
            override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> {
                val page = { i: Int, _: String -> imageWithText(Locator.page(i + 1), ByteArray(1), "page ${i + 1} of the signed lease, clause wombat", 800) }
                return pdfPieces(5, false, { "" }, page, 800)
            }

            override suspend fun extractHead(file: SourceFile, profile: DeviceProfile): List<Piece> {
                heads++
                return extract(file, profile)
            }
        }
        IndexEngine(store, extractor, null, FakeMonitor(), settingsOf()).runBatch(1.minutes, requireCharging = false)
        assertEquals(0, heads, "keyword-only reading never deepens, so it leaves nothing for later")
        assertEquals(5, store.chunkTexts(store.fileByUri(pdf.uri)!!.id).size, "every page's words")
    }

    /** The model went (removed, or the phone became keyword-only) with pages and moments left: they still resolve. */
    @Test
    fun withoutTheModelLeftPagesAreOcrdAndLeftMomentsDropped() = kotlinx.coroutines.runBlocking {
        val store = newStore()
        val pdf = testFile("scan.pdf", Kind.PDF)
        val clip = testFile("clip.mp4", Kind.VIDEO)
        val files = listOf(pdf, clip)
        store.upsertFiles(files)
        val words = listOf("apple", "banana", "cherry", "damson", "elderberry")
        fun page(n: Int) = imageWithText(Locator.page(n), ByteArray(n), "page $n of the signed lease, clause ${n * 7}, ${words[n - 1]}", 800)
        val extractor = object : Extractor {
            override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> = emptyList()

            override suspend fun extractHead(file: SourceFile, profile: DeviceProfile) = if (file.kind == Kind.PDF) {
                pdfPieces(5, false, { "" }, { i, _ -> page(i + 1) }, 800, PDF_HEAD_OCR_PAGES)
            } else {
                listOf(Piece("0:05", listOf(Part.Image(ByteArray(5))), null), later("0:15"))
            }

            override suspend fun later(file: SourceFile, locator: String, profile: DeviceProfile): List<Piece> {
                assertEquals(Kind.PDF, file.kind, "no frame is decoded without the model")
                return page(Locator.pageOf(locator) ?: 0)
            }
        }
        engine(store, extractor).runBatch(1.minutes, requireCharging = false)
        assertEquals(3L, store.laterChunkCount())

        val r = testRetriever(store, files, extractor, modelReady = { false })
        r.indexBatch(1.minutes)

        assertEquals(0L, store.laterChunkCount())
        assertEquals(Locator.page(5), SearchEngine(store, FakeEmbedder()).search("elderberry", SearchEngine.Mode.KEYWORD).hits.single().locator)
        assertEquals(listOf("0:05"), vectorLocators(store).filter { ":" in it }, "the moment read stays; the one left goes")
    }

    /** A photo's words read after its image leave the file's chunks and keyword rows exactly as one read leaves them. */
    @Test
    fun aPhotoReadImageFirstEndsAsOneReadLeavesIt() = runTest {
        val long = (1..12).joinToString(" ") { "Platform $it departs for the harbour at noon." }
        for ((ocr, labels) in listOf(long to "sign\nstreet", "4471" to "door", "" to "")) {
            val read = imageWithText("", ByteArray(4), ocr, 60, labels)
            val (onceDriver, once) = rowStore()
            val (twiceDriver, store) = rowStore()
            val photo = testFile("IMG_1.jpg", Kind.PHOTO)
            listOf(once, store).forEach { it.upsertFiles(listOf(photo)) }
            val whole = object : Extractor {
                override suspend fun extract(file: SourceFile, profile: DeviceProfile) = read
            }
            engine(once, whole).runBatch(1.minutes)
            val split = object : Extractor {
                override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> = error("the model is here: the head is read")

                override suspend fun extractHead(file: SourceFile, profile: DeviceProfile) = imageFirst(ByteArray(4))

                override suspend fun later(file: SourceFile, locator: String, profile: DeviceProfile) = read.also { assertEquals(WORDS, locator) }
            }
            val id = store.fileByUri(photo.uri)!!.id
            IndexEngine(store, split, FakeEmbedder(), FakeMonitor(), settingsOf(), wordsPage = { emptyList() }).runBatch(1.minutes)
            store.countsNow().progress.let {
                assertEquals(listOf(1L, 0L, 0L, 1L), listOf(it.read, it.partlyRead, it.laterPasses, it.wordsWaiting), "read, its words to come")
            }
            assertEquals(emptyMap(), store.readExtents(listOf(id)), "no long file being finished")

            engine(store, split).runBatch(1.minutes)
            assertEquals(rows(onceDriver), rows(twiceDriver), "OCR ${ocr.length} chars")
            assertEquals(once.countsNow().progress, store.countsNow().progress)
        }
    }

    /** Words are first-pass work: read after every pending file, newest photo first, without the deepen pass or a charger. */
    @Test
    fun photosWordsFollowThePendingFilesWithoutTheDeepenPass() = runBlocking {
        val calls = mutableListOf<String>()
        val doc = testFile("lease.docx", Kind.DOC)
        val older = testFile("old.jpg", Kind.PHOTO, modifiedAt = 1)
        val newer = testFile("new.jpg", Kind.PHOTO, modifiedAt = 2)
        val extractor = object : Extractor {
            override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> = error("the model is here: the head is read")

            override suspend fun extractHead(file: SourceFile, profile: DeviceProfile): List<Piece> {
                calls += file.name
                return if (file.kind == Kind.PHOTO) imageFirst(ByteArray(2)) else (1..10).map { textPiece("part $it of the lease") }
            }

            override suspend fun later(file: SourceFile, locator: String, profile: DeviceProfile): List<Piece> {
                calls += "$locator ${file.name}"
                return imageWithText("", ByteArray(2), "Gate 12", 800)
            }
        }
        val store = newStore()
        store.setProfile(PROFILE)
        val files = listOf(doc, older, newer)
        testRetriever(store, files, extractor).indexBatch(1.minutes, deepen = false)
        assertEquals(listOf("lease.docx", "new.jpg", "old.jpg", "words new.jpg", "words old.jpg"), calls)
        assertEquals(0L, store.wordsChunkCount())
        assertEquals(10L - IndexEngine.DOC_HEAD_CHUNKS, store.tailChunkCount(), "the long file's rest waits for the deepen pass")

        // A batch that may not deepen, with no file pending: the words left are its work, not a reason to stop.
        val photo = testFile("late.jpg", Kind.PHOTO, modifiedAt = 3)
        store.upsertFiles(listOf(photo))
        IndexEngine(store, extractor, FakeEmbedder(store.signature()!!), FakeMonitor(), settingsOf(), wordsPage = { emptyList() }).runBatch(1.minutes)
        assertEquals(1L, store.wordsChunkCount())
        assertEquals(NextRun.DONE, testRetriever(store, files + photo, extractor).indexBatch(1.minutes, deepen = false))
        assertEquals("words late.jpg", calls.last())
        assertEquals(0L, store.wordsChunkCount())
        assertEquals(10L - IndexEngine.DOC_HEAD_CHUNKS, store.tailChunkCount())
    }

    @Test
    fun aMomentThatCannotBeReadNowIsAFailedTryNotACrash() = runTest {
        val store = newStore()
        val clip = testFile("clip.mp4", Kind.VIDEO)
        store.upsertFiles(listOf(clip))
        val extractor = object : Extractor {
            override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> = emptyList()

            override suspend fun extractHead(file: SourceFile, profile: DeviceProfile) =
                listOf(Piece("0:05", listOf(Part.Image(ByteArray(5))), null), later("0:15"))
            // No visual(): the default later() has no frame to give.
        }
        engine(store, extractor).runBatch(1.minutes, requireCharging = false)
        repeat(IndexEngine.MAX_TAIL_ATTEMPTS + 1) {
            assertEquals(StopReason.DONE, engine(store, extractor).runBatch(1.minutes, requireCharging = false, deepen = true).stopReason)
        }
        assertEquals(1L, store.chunkCount() - store.vectorCount(), "the placeholder stays, its tries spent")
        assertEquals(0L, store.tailChunkCount())
    }
}
