package app.retriever.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** What the first pass embeds, and what it leaves to the deepen pass, so the first pass is fast and nothing is lost. */
class FirstPassTest {
    private fun vectorLocators(store: Store): List<String> {
        val table = store.loadVectors()
        val ids = (0 until table.size).map { table.chunkIds[it] }
        return store.chunks(ids).values.sortedBy { it.locator }.map { it.locator }
    }

    private fun engine(store: Store, extractor: Extractor, embedder: Embedder = FakeEmbedder()) =
        IndexEngine(store, extractor, embedder, FakeMonitor(), settingsOf())

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
        assertEquals(Modality.IMAGE.ordinal.toByte(), store.loadVectors().modalities[0], "words on a picture leave it a picture")
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
}
