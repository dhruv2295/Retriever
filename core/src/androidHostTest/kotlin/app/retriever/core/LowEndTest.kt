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
    fun anNpuDownloadWaitsForTheTuneThenOpensOnItsOwnDelegate() = runBlocking {
        val store = newStore(sig = null)
        val probe = PIXEL.copy(socModel = "Tensor G5", npuDispatch = true)
        val file = testFile("notes.txt")
        val extractor = FakeExtractor(mapOf(file.name to listOf(textPiece("receipt 48213"))))
        val factory = FakeFactory().apply {
            fail = { profile, build ->
                check(build.isNpu)
                check(profile.backend == Backend.NPU)
                null
            }
        }
        val r = testRetriever(store, listOf(file), extractor, probe, factory).apply { uiVisible = true }
        assertEquals(NextRun.DONE, r.indexBatch(1.minutes, deepen = true))
        assertTrue(factory.opened.isEmpty(), "the words are read; meaning waits for the tune")
        store.setProfile(r.requiredBuild.value.initialProfile())
        assertEquals(NextRun.DONE, r.indexBatch(1.minutes, deepen = true))
        assertEquals(1, factory.opened.size)
        assertEquals(Backend.NPU, factory.opened.single().first.backend)
        assertEquals(1, store.vectorCount())
    }

    @Test
    fun anEightGigabytePhoneReadsAndSearchesBeforeDownloadThenAddsMeaningAfterTheTune() = runBlocking {
        val store = newStore(sig = null)
        val file = testFile("camera.jpg", Kind.PHOTO)
        var reads = 0
        var images = 0
        val extractor = object : Extractor {
            override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> {
                reads++
                return imageWithText("", byteArrayOf(1), "receipt 48213", 800, "beach")
            }
            override suspend fun visual(file: SourceFile, locator: String): Part.Image {
                images++
                return Part.Image(byteArrayOf(1))
            }
        }
        var ready = false
        val factory = FakeFactory()
        val r = testRetriever(store, listOf(file), extractor, factory = factory, modelReady = { ready }).apply { uiVisible = true }
        r.refreshSetUp()
        assertFalse(r.modelPresent.value)
        assertEquals(387_710_976L, r.modelDownloadBytes)
        r.indexBatch(1.minutes)
        assertTrue(factory.opened.isEmpty())
        assertEquals(1, r.search("48213").hits.size)
        assertEquals("Matches: beach", r.search("beach").hits.single().matchReason)
        ready = true
        r.refreshSetUp()
        assertTrue(r.modelPresent.value)
        r.indexBatch(1.minutes, deepen = true)
        assertTrue(factory.opened.isEmpty(), "downloaded but not tuned: no meaning yet")
        store.setProfile(PROFILE)
        r.indexBatch(1.minutes, deepen = true)
        assertEquals(1, reads)
        assertEquals(1, images)
        assertEquals(1, store.vectorCount(), "the picture; its short OCR stays keyword-only")
        assertEquals(1L, r.counts().first().meaning.added)
    }

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
        assertEquals(15L, store.chunkCount())
        assertEquals(3L, store.countsByState()[FileState.INDEXED])
        assertTrue(photo.name in extractor.names, "photos now contribute OCR and labels")

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
