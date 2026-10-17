package app.retriever.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** A phone under [Tiers.MIN_MODEL_RAM_GB] indexes names and text with no model at all. */
class LowEndTest {
    private val threeGb = DeviceProbe(Platform.IOS, 18, "Apple", "iPhone12,8", 3.0, false)

    @Test
    fun aThreeGigabytePhoneSearchesNamesAndTextWithoutTheModel(): Unit = runBlocking {
        val pdf = testFile("lease.pdf", Kind.PDF)
        val photo = testFile("cat.jpg", Kind.PHOTO)
        val shot = testFile("shot.png", Kind.SCREENSHOT)
        val extractor = FakeExtractor(
            mapOf(
                pdf.name to (1..12).map { textPiece("Page $it: the wombat deposit is due monthly.", "p. $it") },
                photo.name to listOf(Piece("", listOf(Part.Image(ByteArray(8))), null)),
                // A screenshot's image piece carries no words of its own: nothing to keep without a model.
                shot.name to listOf(Piece("", listOf(Part.Image(ByteArray(8))), null), textPiece("boarding pass gate 12")),
            ),
        )
        val factory = FakeFactory()
        val monitor = FakeMonitor(DeviceState(3.0, charging = true, 90, Thermal.NONE, screenOn = false))
        val store = newStore(sig = null)
        val r = testRetriever(store, listOf(pdf, photo, shot), extractor, threeGb, factory, monitor, modelReady = { false })
        r.uiVisible = true

        assertTrue(r.textOnly)
        r.refreshSetUp()
        assertEquals(true, r.setUp.value, "no model file and no profile")

        assertEquals(NextRun.DONE, r.indexBatch(1.minutes))
        assertTrue(factory.opened.isEmpty(), "the model is never opened")
        assertEquals(0, store.vectorCount())
        assertEquals(13L, store.chunkCount())
        assertEquals(3L, store.countsByState()[FileState.INDEXED])
        assertFalse(photo.name in extractor.names, "photos are not read")

        val hit = r.search("wombat")
        assertFalse(hit.vectorLane)
        assertEquals("lease.pdf", hit.hits.first().file.name)
        assertEquals("cat.jpg", r.search("cat").hits.single().file.name, "a photo stays findable by name")

        val ids = listOf(pdf, photo, shot).map { store.fileByUri(it.uri)!!.id }
        assertEquals(emptyMap(), r.readExtents(ids))
        assertEquals(0L, r.counts().first().tailFiles)

        r.indexBatch(1.minutes)
        assertEquals(0, store.vectorCount(), "no deepen pass on the charger with the screen off either")
        assertFailsWith<IllegalStateException> { r.calibrate(Samples(listOf(ByteArray(10)), listOf("hello"), emptyList()), false) }
    }

    @Test
    fun fourGigabytesStillRunsTheModel() {
        val r = testRetriever(newStore(), emptyList(), FakeExtractor(), PIXEL.copy(ramGb = 4.0), modelReady = { false })
        assertFalse(r.textOnly)
        runBlocking { r.refreshSetUp() }
        assertEquals(false, r.setUp.value)
    }
}
