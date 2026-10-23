package app.retriever.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The demo's count by the one rule: done is every planned file no longer waiting, of a total fixed at picking. */
class TasteProgressTest {
    private fun test(body: suspend () -> Unit) = runBlocking { withTimeout(20.seconds) { body() } }

    @Test
    fun doneIsEveryFileNoLongerWaiting() {
        val p = TasteProgress.of(30, mapOf(Kind.DOC to 25, Kind.PDF to 5), mapOf(Kind.DOC to 10), mapOf(Kind.DOC to 13, Kind.PDF to 5))
        assertEquals(18, p.read)
        assertEquals(20 to 30, p.done to p.planned, "two found by name only are done, not read")
        assertEquals(mapOf(Kind.DOC to (13 to 25), Kind.PDF to (5 to 5)), p.byKind)
        assertEquals(setOf(Kind.PDF), p.readyKinds)
    }

    /**
     * The owner's run: 117 read of 124 said "All done" beside a full bar. A file found by name only and one Retriever
     * couldn't open are processed too, so the count and the bar both say 30 of 30.
     */
    @Test
    fun nameOnlyAndFailedFilesCountAsProcessed() = test {
        val docs = (1..30).map { testFile("doc$it.txt", Kind.DOC, modifiedAt = it * 7_200_000L) }
        val store = newStore(Signature.of(ModelVariant.M740.generic, PROFILE.visionTokens, PROFILE.maxTokens))
        // doc1 has no words inside: found by name only.
        val pieces = docs.drop(1).associate { it.name to listOf(textPiece("words of ${it.name}")) }
        val r = testRetriever(store, docs, FakeExtractor(pieces)).apply { uiVisible = true }
        r.planTaste()
        store.setProfile(PROFILE)
        r.refreshSetUp()
        store.markFailed(store.fileByUri(docs[1].uri)!!.id, 3) // doc2 couldn't be opened
        r.taste()

        val p = r.tasteProgress().first()
        assertEquals(28, p.read)
        assertEquals("30 of 30 files processed", Words.readOf(p.done.toLong(), p.planned.toLong()))
        assertEquals(1.0, p.done.toDouble() / p.planned)
    }

    /** A planned file deleted mid-demo ends as done: the total never changes after picking. */
    @Test
    fun aFileDeletedMidDemoStillCountsAsDone() = test {
        val docs = (1..30).map { testFile("doc$it.txt", Kind.DOC, modifiedAt = it * 7_200_000L) }
        val store = newStore(Signature.of(ModelVariant.M740.generic, PROFILE.visionTokens, PROFILE.maxTokens))
        val r = testRetriever(store, docs, FakeExtractor())
        r.planTaste()
        store.removeFiles(listOf(docs.last().uri))
        val p = r.tasteProgress().first()
        assertEquals(30 to 1, p.planned to p.done)
    }

    /** Q1 3: Name only and Couldn't read files are done without being read, so they are not still to read. */
    @Test
    fun filesDoneWithoutContentAreNotPending() {
        val p = TasteProgress.of(3, mapOf(Kind.PDF to 3), emptyMap(), mapOf(Kind.PDF to 1))
        assertEquals(0, p.pending)
        assertEquals(3, p.done)
        assertEquals(setOf(Kind.PDF), p.readyKinds)
    }

    @Test
    fun theFlowFollowsTheRead() = test {
        val docs = (1..30).map { testFile("doc$it.txt", Kind.DOC, modifiedAt = it * 7_200_000L) }
        val store = newStore(Signature.of(ModelVariant.M740.generic, PROFILE.visionTokens, PROFILE.maxTokens))
        var n = 0
        var stopAt = 20
        lateinit var r: Retriever
        val extractor = FakeExtractor(docs.associate { it.name to listOf(textPiece("words of ${it.name}")) }) { if (++n == stopAt) r.cancelBatch() }
        r = testRetriever(store, docs, extractor).apply { uiVisible = true }
        assertTrue(r.planTaste().everything)
        store.setProfile(PROFILE)
        r.refreshSetUp()

        r.taste() // the 20th file gives its attempt back
        assertEquals(19 to 30, r.tasteProgress().first().let { it.done to it.planned })
        stopAt = 22 // the 21st extract re-reads the 20th file
        r.taste()
        assertEquals(20 to 30, r.tasteProgress().first().let { it.done to it.planned })
    }

    /** No model, no chips: twenty words-only files are enough, and photos aren't planned. */
    @Test
    fun textOnlyReadsItsKindsAndNeedsNoChip() = test {
        val shots = (1..25).map { testFile("shot$it.png", Kind.SCREENSHOT, modifiedAt = it * 7_200_000L) }
        val photos = (1..5).map { testFile("p$it.jpg", Kind.PHOTO, modifiedAt = it * 7_200_000L) }
        val store = newStore(sig = null)
        var n = 0
        lateinit var r: Retriever
        // Labels alone make a photo Read with no model, so all 20 reads count.
        val pieces = shots.associate { it.name to listOf(textPiece("words of ${it.name}")) } +
            photos.associate { it.name to imageWithText("", byteArrayOf(1), "", 800, "sky") }
        val extractor = FakeExtractor(pieces) { if (++n == 20) r.cancelBatch() }
        r = testRetriever(store, shots + photos, extractor, DeviceProbe(Platform.ANDROID, 35, "Google", "Pixel", 3.0, false), modelReady = { false })
            .apply { uiVisible = true }
        assertTrue(r.textOnly)
        r.refreshSetUp()
        val plan = r.planTaste()
        assertEquals(setOf(Kind.SCREENSHOT, Kind.PHOTO), plan.byKind.keys)

        r.taste()
        assertEquals(20, r.tasteProgress().first().read)
    }
}
