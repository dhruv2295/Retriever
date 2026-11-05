package app.retriever.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Every page and the whole of a text file reach keyword search; vectors stay head-first and deepen later. */
class CoverageTest {
    private suspend fun index(store: Store, pieces: Map<String, List<Piece>>, deepen: Boolean = false) =
        IndexEngine(store, FakeExtractor(pieces), FakeEmbedder(), FakeMonitor(), settingsOf())
            .runBatch(9.minutes, requireCharging = false, deepen = deepen)

    /** What both extractors make of a plain text file under [TEXT_BYTES]. */
    private fun textFile(text: String) = Chunker.chunks(text).map { Piece("", listOf(Part.Text(it)), it) }

    private suspend fun keyword(store: Store, q: String) = SearchEngine(store, FakeEmbedder()).search(q, SearchEngine.Mode.KEYWORD).hits

    @Test
    fun aTextFileOver300KbIsSearchableToItsEnd() = runTest {
        val words = List(60_000) { "line$it filler" }.joinToString(" ") + " quokka"
        assertTrue(words.length in 300 * 1024..TEXT_BYTES, "${words.length} bytes")
        val store = newStore()
        val file = testFile("notes.txt")
        store.upsertFiles(listOf(file))

        index(store, mapOf(file.name to textFile(words)))

        assertEquals("notes.txt", keyword(store, "quokka").single().file.name)
        assertEquals(IndexEngine.DOC_HEAD_CHUNKS, store.vectorCount(), "only the opening is embedded on the first pass")
        assertTrue(store.chunkCount() > 300)
    }

    @Test
    fun everyPageOfA40PagePdfIsSearchable() = runTest {
        assertTrue(PDF_TEXT_PAGES >= 40)
        val store = newStore()
        val pdf = testFile("report.pdf", Kind.PDF)
        store.upsertFiles(listOf(pdf))
        val pages = (1..40).map { p -> textPiece("Page $p of the annual report, ${if (p == 40) "wombat" else "budget"} figures.", "p. $p") }

        index(store, mapOf(pdf.name to pages))

        assertEquals("p. 40", keyword(store, "wombat").single().locator)
    }

    @Test
    fun aCutShortFileReadsAsItsFirstPartUntilItChanges() = runTest {
        val store = newStore()
        val whole = testFile("short.txt")
        val cut = testFile("huge.txt")
        store.upsertFiles(listOf(whole, cut))
        index(store, mapOf(whole.name to listOf(textPiece("all of it")), cut.name to listOf(textPiece("the first part"), cutShort())))

        val ids = listOf(whole, cut).map { store.fileByUri(it.uri)!!.id }
        assertEquals(mapOf(ids[1] to ReadExtent.FIRST_PART), store.readExtents(ids))
        assertTrue(store.fileByUri(cut.uri)!!.partial)
        assertEquals(2L, store.chunkCount(), "the marker is never stored")

        store.upsertFiles(listOf(cut.copy(modifiedAt = 9)))
        assertEquals(emptyMap(), store.readExtents(ids), "a changed file is read again from the start")
    }

    @Test
    fun tailChunksMakeAFileDeepeningAndCountAsOneFile() = runTest {
        val store = newStore()
        val files = listOf(testFile("a.txt"), testFile("b.txt"), testFile("c.txt"))
        store.upsertFiles(files)
        val long = (1..20).map { textPiece("part $it") }
        index(store, mapOf("a.txt" to long, "b.txt" to long, "c.txt" to listOf(textPiece("short"))))

        val ids = files.map { store.fileByUri(it.uri)!!.id }
        assertEquals(mapOf(ids[0] to ReadExtent.DEEPENING, ids[1] to ReadExtent.DEEPENING), store.readExtents(ids))
        val counts = store.countsNow()
        assertEquals(2L * (20 - IndexEngine.DOC_HEAD_CHUNKS), counts.tailChunks)
        assertEquals(2L, counts.tailFiles)

        index(store, emptyMap(), deepen = true)
        assertEquals(emptyMap(), store.readExtents(ids))
        assertEquals(0L, store.countsNow().tailFiles)
    }

    @Test
    fun anOpenAppDeepensOnBatteryOnceNothingIsPending() = runBlocking {
        suspend fun run(uiVisible: Boolean): Store {
            val store = newStore()
            val file = testFile("big.txt")
            val r = testRetriever(
                store,
                listOf(file),
                FakeExtractor(mapOf(file.name to (1..20).map { textPiece("part $it") })),
                // Above ReadingPolicy.BATTERY_FLOOR, where Also on battery reads off the charger.
                monitor = FakeMonitor(DeviceState(8.0, charging = false, 80, Thermal.NONE, screenOn = uiVisible)),
            )
            store.setProfile(PROFILE)
            r.uiVisible = uiVisible
            r.setReadingSchedule(ReadingSchedule.BATTERY)
            assertEquals(NextRun.DONE, r.indexBatch(1.minutes))
            return store
        }
        assertEquals(0L, run(uiVisible = true).tailChunkCount(), "the open app embedded the tail in the same batch")
        assertEquals(0L, run(uiVisible = false).tailChunkCount(), "battery schedule also deepens with the screen off")
    }

    @Test
    fun aPdfPageFoundByItsPictureShowsNoTextWithoutAQueryWord() = runTest {
        val store = newStore()
        val pdf = testFile("Document_04.pdf", Kind.PDF)
        store.upsertFiles(listOf(pdf))
        // FakeEmbedder puts an image of n bytes on dim n: the page picture matches "zebra" exactly, its words don't.
        val zebra = kotlin.math.abs("zebra".hashCode()) % DIMS
        val page = listOf(Piece("p. 1", listOf(Part.Image(ByteArray(zebra))), null), textPiece("Priya Sharma, résumé", "p. 1"))
        index(store, mapOf(pdf.name to page))

        val hit = SearchEngine(store, FakeEmbedder()).search("zebra", SearchEngine.Mode.VECTOR).hits.single()
        assertEquals("p. 1", hit.locator)
        assertEquals(null, hit.snippet)
    }

    @Test
    fun aReadThatMustNotDeepenEndsOnceNothingIsPending() = runBlocking {
        val store = newStore()
        val file = testFile("big.txt")
        val r = testRetriever(store, listOf(file), FakeExtractor(mapOf(file.name to (1..20).map { textPiece("part $it") })))
        store.setProfile(PROFILE)
        // As iOS keeps it for the person's continued read: visible, so deepening would otherwise run on battery.
        r.uiVisible = true
        assertEquals(NextRun.DONE, r.indexBatch(1.minutes, deepen = false))
        assertEquals(0L, store.countsByState()[FileState.PENDING] ?: 0L)
        assertEquals(20L - IndexEngine.DOC_HEAD_CHUNKS, store.tailChunkCount(), "the tail is left for a later batch")
    }
}
