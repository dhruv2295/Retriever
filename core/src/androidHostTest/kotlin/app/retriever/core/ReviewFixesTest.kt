package app.retriever.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class ReviewFixesTest {
    private suspend fun index(store: Store, extractor: Extractor, embedder: Embedder = FakeEmbedder(), deepen: Boolean = false) =
        IndexEngine(store, extractor, embedder, FakeMonitor(), settingsOf()).runBatch(9.minutes, requireCharging = false, deepen = deepen)

    @Test
    fun kindFilterAppliesBeforeTheTopKCut() = runTest {
        val store = newStore()
        val e = FakeEmbedder()
        val photos = (1..150).map { testFile("p$it.jpg", Kind.PHOTO) }
        val pdfs = (1..5).map { testFile("d$it.pdf", Kind.PDF) }
        store.upsertFiles(photos + pdfs)
        for (f in photos + pdfs) {
            // Photos outrank the PDFs in both lanes, so an unfiltered top 100 holds no PDF.
            val text = if (f.kind == Kind.PHOTO) "electricity bill electricity bill" else "electricity bill paid"
            store.replaceChunks(store.fileByUri(f.uri)!!.id, listOf(Store.NewChunk(1, "", text, e.embed(listOf(Part.Text(text))))))
        }
        val hits = SearchEngine(store, e).search("electricity bill", kinds = setOf(Kind.PDF)).hits
        assertEquals(pdfs.map { it.name }.toSet(), hits.map { it.file.name }.toSet())
        assertEquals(5, SearchEngine(store, e).searchImage(ByteArray(3), kinds = setOf(Kind.PDF)).hits.size)
    }

    @Test
    fun aRescanWritesANewKindOrMime() {
        val store = newStore()
        val f = testFile("a.docx", Kind.OTHER).copy(mime = "application/octet-stream")
        store.upsertFiles(listOf(f))
        store.upsertFiles(listOf(f.copy(kind = Kind.DOC, mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document")))
        val stored = store.fileByUri(f.uri)!!.file
        assertEquals(Kind.DOC, stored.kind)
        assertTrue("wordprocessingml" in stored.mime)
    }

    @Test
    fun filesOnAnUnscannedVolumeSurviveBothSyncs() = runTest {
        val store = newStore()
        val source = FakeSource()
        val sync = LibrarySync(store, source, now = { 0L })
        val main = testFile("a.jpg", Kind.PHOTO).copy(uri = "content://media/external_primary/file/1")
        val gone = main.copy(uri = "content://media/external_primary/file/2", name = "b.jpg")
        val sd = main.copy(uri = "content://media/1234-5678/file/1", name = "c.jpg")
        val both = setOf("content://media/external_primary/file/", "content://media/1234-5678/file/")
        val primaryOnly = setOf("content://media/external_primary/file/")
        source.next = Changes(listOf(main, gone, sd), emptyList(), "t1", isFullScan = true, scope = both)
        sync.sync()

        // The card is out: an incremental sync sees only the primary volume.
        source.next = Changes(emptyList(), emptyList(), "t2", present = setOf(main.uri), scope = primaryOnly)
        assertEquals(1, sync.sync().removed, "only the deleted primary file goes")
        assertTrue(sd.uri in store.knownUris())

        source.next = Changes(listOf(main), emptyList(), "t3", isFullScan = true, scope = primaryOnly)
        sync.sync(forceFull = true)
        assertEquals(setOf(main.uri, sd.uri), store.knownUris())
    }

    @Test
    fun aVideoMomentsTimestampsAreNotKeywords() = runTest {
        val store = newStore()
        val video = testFile("v.mp4", Kind.VIDEO)
        store.upsertFiles(listOf(video))
        val moment = Piece("0:30", momentParts(ByteArray(5), 37_000, ShortArray(16)), null)
        index(store, FakeExtractor(mapOf("v.mp4" to listOf(moment))))
        assertTrue(store.keywordSearch("37", 10).isEmpty())
        val id = store.fileByUri(video.uri)!!.id
        assertNull(store.chunks(listOf(id)).values.firstOrNull()?.text)
    }

    @Test
    fun aTailTooLongForTheModelIsSplitNotTruncated() = runTest {
        val store = newStore()
        val doc = testFile("long.txt")
        store.upsertFiles(listOf(doc))
        val words = (1..200).joinToString(" ") { "w$it" }
        store.replaceChunks(store.fileByUri(doc.uri)!!.id, listOf(Store.NewChunk(1, "p. 9", words, null)))
        // Rejects any text over 600 chars, as the runtime rejects inputs over its signature length.
        val picky = object : Embedder by FakeEmbedder() {
            val inner = FakeEmbedder()
            override suspend fun embedRaw(parts: List<Part>): FloatArray {
                if ((parts.single() as Part.Text).text.length > 600) throw InputTooLongException("too long")
                return inner.embedRaw(parts)
            }
        }
        index(store, FakeExtractor(), picky, deepen = true)
        assertEquals(0L, store.tailChunkCount())
        assertEquals(2L, store.chunkCount(), "two halves, each with a vector")
        assertEquals(1, store.keywordSearch("w200", 10).size, "the second half is still there")
    }

    @Test
    fun aTailThatKeepsFailingIsGivenUpAfterThreeTries() = runTest {
        val store = newStore()
        val doc = testFile("t.txt")
        store.upsertFiles(listOf(doc))
        store.replaceChunks(store.fileByUri(doc.uri)!!.id, listOf(Store.NewChunk(1, "", "some tail text", null)))
        var calls = 0
        val failing = object : Embedder by FakeEmbedder() {
            override suspend fun embedRaw(parts: List<Part>): FloatArray {
                calls++
                throw IllegalStateException("tokenizer fault")
            }
        }
        assertEquals(StopReason.DONE, index(store, FakeExtractor(), failing, deepen = true).stopReason)
        assertEquals(3, calls)
        assertEquals(0L, store.tailChunkCount())
        assertEquals(1, store.keywordSearch("tail", 10).size, "it stays keyword-searchable")
    }

    @Test
    fun aStopMidFileGivesTheAttemptBack() = runTest {
        val store = newStore()
        val doc = testFile("a.txt")
        store.upsertFiles(listOf(doc))
        val stopping = FakeExtractor(onExtract = { throw CancellationException("worker stopped") })
        assertFailsWith<CancellationException> { index(store, stopping) }
        val stored = store.fileByUri(doc.uri)!!
        assertEquals(0, stored.attempts)
        assertEquals(FileState.PENDING, stored.state)
    }

    @Test
    fun ramRoundsUpToTheMarketedSize() {
        assertEquals(6.0, nominalRamGb(5_500_000_000)) // totalMem of a 6 GB phone below API 34
        assertEquals(8.0, nominalRamGb(7_500_000_000))
        assertEquals(8.0, nominalRamGb(8_000_000_000)) // advertisedMem in decimal GB
        assertEquals(8.0, nominalRamGb(8L shl 30))
        assertEquals(6.0, nominalRamGb((5.6 * (1L shl 30)).toLong())) // a 6 GB iPhone's physicalMemory
        assertEquals(4.0, nominalRamGb(3_700_000_000))
    }

    @Test
    fun aMomentJumpsToWhatTheModelHeardOrSaw() {
        val m = moments(durationMs = 60 * 60_000L, everySec = 5, audioSec = 10).first()
        assertEquals(timeLocator(((m.frameMs - 5_000) / 1000).toInt()), momentLocator(m, withAudio = true))
        assertEquals(timeLocator((m.frameMs / 1000).toInt()), momentLocator(m, withAudio = false))
        assertTrue(m.frameMs > 60_000, "a long video's segment is minutes long, so the start would be far off")
    }
}
