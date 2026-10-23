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
        val book = SpeedBook()
        val p = TasteProgress.of(30, mapOf(Kind.DOC to 25, Kind.PDF to 5), mapOf(Kind.DOC to 10), 0, book, mapOf(Kind.DOC to 13, Kind.PDF to 5), null)
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

    /** The time left is measured wall time: never a seed's guess, never before the session has a ratio. */
    @Test
    fun secondsLeftIsInferenceTimesTheWallRatioOnceMeasured() {
        val docs = SpeedBook().add(Kind.DOC, 2_000.0, 1.0) // 2 s of inference a document
        fun left(book: SpeedBook, ratio: Double?, pending: Map<Kind, Int> = mapOf(Kind.DOC to 10)) =
            TasteProgress.of(25, mapOf(Kind.DOC to 20, Kind.PDF to 5), pending, 0, book, mapOf(Kind.DOC to 10), ratio).secondsLeft
        assertEquals(null, left(docs, null), "no ratio before a share of the budget is read")
        assertEquals(null, left(SpeedBook(), 3.0), "a seeded pace is not a measurement")
        assertEquals(null, left(docs, 3.0, mapOf(Kind.DOC to 10, Kind.PDF to 1)), "a pending kind with no pace yet")
        assertEquals(60, left(docs, 3.0), "10 documents × 2 s × 3")
        assertEquals(0, left(docs, 3.0, emptyMap()))
        assertEquals(null, TasteProgress.of(0, emptyMap(), emptyMap(), 0, docs, emptyMap(), 3.0).secondsLeft, "no plan")
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
        assertEquals(null, eta.shownRatio(90_000.0), "20 s of wall time is under a quarter of a 90 s budget")
        assertEquals(20_000.0 / 12_000, eta.shownRatio(80_000.0))
    }

    /**
     * Run 4's shape: wall time about five times inference, so a trimmed 90 s sample holds under 20 s of inference. The
     * time shows once a quarter of the budget has passed in wall time, never on a batch's first file alone (exactly 1).
     */
    @Test
    fun theTimeShowsOnASlowPhoneOnceAShareOfTheBudgetHasPassed() {
        var now = 0L
        val eta = Eta(SpeedBook()) { now }
        eta.start(mapOf(Kind.PHOTO to 30L), 0)
        now = 30_000
        eta.add(Kind.PHOTO, 30_000.0, 1.0) // one long first file: a ratio of exactly 1, not a measurement
        assertEquals(null, eta.shownRatio(90_000.0))
        val eta5 = Eta(SpeedBook()) { now }
        eta5.start(mapOf(Kind.PHOTO to 30L), 0)
        now = 0
        repeat(6) {
            now += 5_000
            eta5.add(Kind.PHOTO, 1_000.0, 1.0)
        }
        // The first file's own 1 s, then five 5 s gaps: 26 s of wall time over 6 s of inference.
        assertEquals(26.0 / 6, eta5.shownRatio(90_000.0)!!, 1e-9)
    }

    /** Q1 3: Name only and Couldn't read files are done without being read, so they are not still to read. */
    @Test
    fun filesDoneWithoutContentAreNotPending() {
        val p = TasteProgress.of(3, mapOf(Kind.PDF to 3), emptyMap(), 0, SpeedBook(), mapOf(Kind.PDF to 1), null)
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
