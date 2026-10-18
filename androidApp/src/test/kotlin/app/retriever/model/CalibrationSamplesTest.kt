package app.retriever.model

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.retriever.core.Backend
import app.retriever.core.DIMS
import app.retriever.core.Embedder
import app.retriever.core.Kind
import app.retriever.core.Part
import app.retriever.core.Signature
import app.retriever.core.SourceFile
import app.retriever.core.Tier
import app.retriever.indexing.IndexService
import app.retriever.testFile
import app.retriever.testRetriever
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real WebP decoding and JPEG encoding of the bundled illustrations
class CalibrationSamplesTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private class Fast : Embedder {
        override val signature = Signature("fast")
        override val backend = Backend.CPU
        val images: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())

        override suspend fun embedRaw(parts: List<Part>): FloatArray {
            parts.filterIsInstance<Part.Image>().forEach { images += it.jpeg }
            return FloatArray(DIMS) { 1f }
        }

        override fun close() {}
    }

    /** No photo is reachable: the three bundled illustrations stand in, so a fast phone is not tiered LIGHT (F029). */
    @Test
    fun withNoPhotosTheIllustrationsKeepAFastPhoneOffLight() = runBlocking {
        val embedder = Fast()
        val extracted = mutableListOf<SourceFile>()
        val files = listOf(testFile("a.pdf", Kind.PDF), testFile("b.docx", Kind.DOC))
        val retriever = testRetriever(files, extract = {
            extracted += it
            emptyList()
        }, embedder = embedder)
        val result = Calibrator(app, retriever, IndexService(app, retriever)).calibrate(tuneAgain = false)

        assertEquals(3, embedder.images.map { it.contentHashCode() }.toSet().size)
        assertTrue(embedder.images.all { it.isNotEmpty() })
        assertNotEquals(Tier.LIGHT, result.profile.tier)
        assertEquals("no PDF or document is read for a tune", emptyList<SourceFile>(), extracted)
    }
}
