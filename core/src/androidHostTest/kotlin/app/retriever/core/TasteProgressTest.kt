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
        assertFalse(TasteProgress.of(mapOf(Kind.DOC to 30), mapOf(Kind.DOC to 11), 0, book, mapOf(Kind.DOC to 19)).readyToTry)
        val twenty = TasteProgress.of(mapOf(Kind.DOC to 25, Kind.PDF to 5), mapOf(Kind.DOC to 10), 0, book, mapOf(Kind.DOC to 15, Kind.PDF to 5))
        assertTrue(twenty.readyToTry)
        assertEquals(20, twenty.read)
        assertEquals(mapOf(Kind.DOC to (15 to 25), Kind.PDF to (5 to 5)), twenty.byKind)
        assertEquals(setOf(Kind.PDF), twenty.readyKinds)
        assertEquals(2, twenty.secondsLeft, "10 docs at the 200 ms seed")
        val small = TasteProgress.of(mapOf(Kind.PDF to 5), emptyMap(), 0, book, mapOf(Kind.PDF to 5))
        assertTrue(small.readyToTry, "a five-file plan is done before twenty")
        assertEquals(0, small.secondsLeft)
        assertEquals(null, TasteProgress.of(emptyMap(), emptyMap(), 0, book, emptyMap()).secondsLeft)
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
