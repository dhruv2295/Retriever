package app.retriever.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** "Try it now" shows at 20 files read, or sooner when the plan is smaller. */
class TasteProgressTest {
    private fun test(body: suspend () -> Unit) = runBlocking { withTimeout(20.seconds) { body() } }

    @Test
    fun readyAtTwentyFilesOrWhenThePlanFinishes() {
        val book = SpeedBook()
        assertFalse(TasteProgress.of(mapOf(Kind.DOC to 30), mapOf(Kind.DOC to 11), 0, book, mapOf(Kind.DOC to 19), null).readyToTry)
        val twenty = TasteProgress.of(mapOf(Kind.DOC to 25, Kind.PDF to 5), mapOf(Kind.DOC to 10), 0, book, mapOf(Kind.DOC to 15, Kind.PDF to 5), null)
        assertTrue(twenty.readyToTry)
        assertEquals(20, twenty.read)
        assertEquals(mapOf(Kind.DOC to (15 to 25), Kind.PDF to (5 to 5)), twenty.byKind)
        assertEquals(setOf(Kind.PDF), twenty.readyKinds)
        val small = TasteProgress.of(mapOf(Kind.PDF to 5), emptyMap(), 0, book, mapOf(Kind.PDF to 5), null)
        assertTrue(small.readyToTry, "a five-file plan is done before twenty")
    }

    /** The time left is measured wall time: never a seed's guess, never before the session has a ratio. */
    @Test
    fun secondsLeftIsInferenceTimesTheWallRatioOnceMeasured() {
        val docs = SpeedBook().add(Kind.DOC, 2_000.0, 1.0) // 2 s of inference a document
        fun left(book: SpeedBook, ratio: Double?, pending: Map<Kind, Int> = mapOf(Kind.DOC to 10)) =
            TasteProgress.of(mapOf(Kind.DOC to 20, Kind.PDF to 5), pending, 0, book, mapOf(Kind.DOC to 10), ratio).secondsLeft
        assertEquals(null, left(docs, null), "no ratio before SHOW_AFTER_MS of inference")
        assertEquals(null, left(SpeedBook(), 3.0), "a seeded pace is not a measurement")
        assertEquals(null, left(docs, 3.0, mapOf(Kind.DOC to 10, Kind.PDF to 1)), "a pending kind with no pace yet")
        assertEquals(60, left(docs, 3.0), "10 documents × 2 s × 3")
        assertEquals(0, left(docs, 3.0, emptyMap()))
        assertEquals(null, TasteProgress.of(emptyMap(), emptyMap(), 0, docs, emptyMap(), 3.0).secondsLeft, "no plan")
    }

    /** The ratio counts the gaps between files within a batch; the first file of a batch counts its own time, so a pause adds nothing. */
    @Test
    fun theWallRatioSkipsTheTimeBetweenBatches() {
        var now = 0L
        val eta = Eta(SpeedBook()) { now }
        eta.start(mapOf(Kind.DOC to 5L), 0)
        now = 10_000
        eta.add(Kind.DOC, 4_000.0, 1.0) // the batch's first file: its own 4 s
        now = 22_000
        eta.add(Kind.DOC, 4_000.0, 1.0) // 12 s after the one before
        assertEquals(16_000.0, eta.wallMs)
        assertEquals(2.0, eta.wallRatio)
        now = 600_000 // ten minutes away, then a new batch
        eta.start(mapOf(Kind.DOC to 3L), 0)
        now = 604_000
        eta.add(Kind.DOC, 4_000.0, 1.0)
        assertEquals(20_000.0, eta.wallMs)
        assertEquals(null, eta.shownRatio, "12 s of inference is under SHOW_AFTER_MS")
    }

    /** Q1 3: Name only and Couldn't read files are done without being read, so they are not still to read. */
    @Test
    fun filesDoneWithoutContentAreNotPending() {
        val p = TasteProgress.of(mapOf(Kind.PDF to 3), emptyMap(), 0, SpeedBook(), mapOf(Kind.PDF to 1), null)
        assertEquals(0, p.pending)
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
        assertEquals(19, r.tasteProgress().first().read)
        assertFalse(r.tasteProgress().first().readyToTry)
        stopAt = 22 // the 21st extract re-reads the 20th file
        r.taste()
        assertEquals(20, r.tasteProgress().first().read)
        assertTrue(r.tasteProgress().first().readyToTry)
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
        val p = r.tasteProgress().first()
        assertEquals(20, p.read)
        assertTrue(p.readyToTry)
    }
}
