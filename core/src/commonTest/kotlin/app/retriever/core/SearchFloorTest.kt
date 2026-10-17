package app.retriever.core

import kotlinx.coroutines.test.runTest
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SearchEngine's strong/weak split. The default floors (photo 0.69, screenshot 0.73, text 0.69) were measured on the
 * eval corpus for M440 / vt70 / d256 int8 / CPU on the iOS simulator (eval/REPORT.md, "Relevance floor (wave 4)"):
 * lowest right file found by meaning 0.697-0.707 / 0.754 / 0.703, highest no-match file 0.691 / 0.703 / 0.675.
 * The cosines below sit at least 0.09 from a floor, well clear of int8 rounding (under 0.01).
 */
class SearchFloorTest {
    private val sig = Signature("M440/0123abcd/vt70/d256/t512")

    /** Every query is the first axis, so a file's cosine is the first component of its unit vector. */
    private class AxisEmbedder(override val signature: Signature) : Embedder {
        override val backend = Backend.CPU

        override suspend fun embedRaw(parts: List<Part>) = FloatArray(768).also { it[0] = 1f }

        override fun close() {}
    }

    /** A file whose one chunk has cosine [cos] with every query (its rest on its own axis), and [text] for the keyword lane. */
    private class F(val name: String, val kind: Kind, val cos: Float, val text: String? = null)

    private suspend fun engine(files: List<F>, floor: RelevanceFloor = RelevanceFloor()): SearchEngine {
        val store = Store(testDriver())
        store.resetForModel(sig)
        val sources = files.map { SourceFile("folder://documents/Docs/${it.name}", it.name, "Docs", "application/octet-stream", it.kind, 100, 1) }
        store.upsertFiles(sources)
        files.forEachIndexed { i, f ->
            val raw = FloatArray(768).also { it[0] = f.cos; it[i + 1] = sqrt(1 - f.cos * f.cos) }
            store.replaceChunks(checkNotNull(store.fileByUri(sources[i].uri)).id, listOf(Store.NewChunk(0, "", f.text, Vector.of(raw, sig))))
        }
        return SearchEngine(store, AxisEmbedder(sig), floorFor = { floor })
    }

    @Test
    fun onlyTheMeasuredModelAndVisionTokensGetAFloor() {
        assertEquals(RelevanceFloor(), RelevanceFloor.measuredFor(sig))
        assertEquals(null, RelevanceFloor.measuredFor(Signature("M740/0123abcd/vt70/d256/t512")))
        assertEquals(null, RelevanceFloor.measuredFor(Signature("M440/0123abcd/vt140/d256/t512")))
    }

    @Test
    fun anUnmeasuredModelCallsEveryHitStrong() = runTest {
        val files = listOf(F("a.jpg", Kind.PHOTO, 0.10f), F("b.jpg", Kind.PHOTO, 0.20f))
        val m740 = Signature("M740/0123abcd/vt70/d256/t512")
        val store = Store(testDriver()).also { it.resetForModel(m740) }
        val sources = files.map { SourceFile("folder://documents/Docs/${it.name}", it.name, "Docs", "image/jpeg", it.kind, 100, 1) }
        store.upsertFiles(sources)
        files.forEachIndexed { i, f ->
            val raw = FloatArray(768).also { it[0] = f.cos; it[i + 1] = sqrt(1 - f.cos * f.cos) }
            store.replaceChunks(checkNotNull(store.fileByUri(sources[i].uri)).id, listOf(Store.NewChunk(0, "", null, Vector.of(raw, m740))))
        }
        val r = SearchEngine(store, AxisEmbedder(m740)).search("a dog on the grass")
        assertEquals(2, r.hits.size)
        assertEquals(2, r.strongCount)
    }

    private fun names(r: SearchEngine.SearchResult) = r.hits.map { it.file.name }

    @Test
    fun aVectorOnlyFileAboveItsFloorIsStrongAndOneBelowIsListedAfter() = runTest {
        val r = engine(listOf(F("low.jpg", Kind.PHOTO, 0.60f), F("high.jpg", Kind.PHOTO, 0.80f))).search("a dog on the grass")
        assertEquals(listOf("high.jpg", "low.jpg"), names(r))
        assertEquals(1, r.strongCount)
    }

    @Test
    fun weakFilesFollowEveryStrongFileEachInFusedOrder() = runTest {
        // Vector order is shot, doc, photo, note; the screenshot's floor is above its cosine, so it drops behind the strong ones.
        val floor = RelevanceFloor(photo = 0.5f, screenshot = 0.9f, text = 0.3f)
        val files = listOf(
            F("photo.jpg", Kind.PHOTO, 0.6f),
            F("Screenshot 1.png", Kind.SCREENSHOT, 0.8f),
            F("doc.pdf", Kind.PDF, 0.7f),
            F("note.txt", Kind.DOC, 0.1f),
        )
        val r = engine(files, floor).search("anything at all", SearchEngine.Mode.VECTOR)
        assertEquals(listOf("doc.pdf", "photo.jpg", "Screenshot 1.png", "note.txt"), names(r))
        assertEquals(2, r.strongCount)
    }

    @Test
    fun aKeywordMatchIsStrongWhateverItsCosine() = runTest {
        val r = engine(listOf(F("bill.pdf", Kind.PDF, 0.10f, "zebra crossing fine"), F("other.pdf", Kind.PDF, 0.20f, "nothing here"))).search("zebra")
        assertEquals("bill.pdf", r.hits.first().file.name)
        assertEquals(1, r.strongCount)
        assertTrue(r.vectorLane)
    }

    @Test
    fun whenNothingPassesHitsStillComeBackAllWeak() = runTest {
        val files = listOf(F("a.jpg", Kind.PHOTO, 0.55f), F("Screenshot 2.png", Kind.SCREENSHOT, 0.60f), F("c.pdf", Kind.PDF, 0.50f, "lease terms"))
        val r = engine(files).search("passport renewal form")
        assertEquals(0, r.strongCount)
        assertEquals(3, r.hits.size)
    }

    @Test
    fun searchByPhotoCountsEveryHitStrong() = runTest {
        val r = engine(listOf(F("a.jpg", Kind.PHOTO, 0.10f), F("b.jpg", Kind.PHOTO, 0.20f))).searchImage(byteArrayOf(1, 2, 3))
        assertEquals(2, r.hits.size)
        assertEquals(r.hits.size, r.strongCount)
    }
}
