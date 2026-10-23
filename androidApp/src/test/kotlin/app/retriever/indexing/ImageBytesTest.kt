package app.retriever.indexing

import android.app.Application
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import app.retriever.core.OCR_SHORT_PX
import app.retriever.core.OCR_WIDTH
import app.retriever.core.OcrStrips
import app.retriever.core.android.ImageBytes
import app.retriever.core.ocrStrips
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves files from the app's cache dir and counts thumbnail requests (ContentResolver.loadThumbnail's EXTRA_SIZE).
 * Opens for an image type (ImageDecoder's and loadThumbnail's) fail, so ImageDecoder falls back to the stream the test
 * registers: Robolectric decodes streams, not file descriptors. Opens for any type (openFileDescriptor's) are served.
 */
class FixtureProvider : ContentProvider() {
    var thumbnailRequests = 0

    override fun onCreate() = true

    override fun getType(uri: Uri) = if (uri.path!!.endsWith(".png")) "image/png" else "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(File(context!!.cacheDir, uri.lastPathSegment!!), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?): AssetFileDescriptor? {
        if (opts?.containsKey(ContentResolver.EXTRA_SIZE) == true) thumbnailRequests++
        if (mimeTypeFilter != "*/*") throw FileNotFoundException("image opens are not served")
        return super.openTypedAssetFile(uri, mimeTypeFilter, opts)
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sortOrder: String?) = null

    override fun insert(uri: Uri, values: ContentValues?) = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0

    override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
}

/** The pixels the extractor hands the model, the labeler and OCR. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real JPEG and PNG coding, ImageDecoder and BitmapRegionDecoder
class ImageBytesTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val provider = Robolectric.setupContentProvider(FixtureProvider::class.java, AUTHORITY)

    private fun serve(name: String, bitmap: Bitmap, format: Bitmap.CompressFormat): Uri {
        val file = File(app.cacheDir, name)
        file.outputStream().use { bitmap.compress(format, 90, it) }
        return Uri.parse("content://$AUTHORITY/$name").also { shadowOf(app.contentResolver).registerInputStreamSupplier(it) { file.inputStream() } }
    }

    private fun size(jpeg: ByteArray) = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size).let { it.width to it.height }

    @Test
    fun aPhotoIsEmbeddedFromItsOwnDecodeNeverMediaProvidersThumbnail() {
        val uri = serve("photo.jpg", createBitmap(2000, 1500).apply { eraseColor(Color.GRAY) }, Bitmap.CompressFormat.JPEG)

        // The first read's two steps: the decode for OCR, and the 512 px copy of it the model and labeler get.
        val ocr = ImageBytes.bitmap(app, uri, OCR_WIDTH, maxShort = OCR_SHORT_PX)!!
        assertEquals(1440 to 1080, ocr.width to ocr.height)
        val thumb = ImageBytes.scaled(ocr, 512, 512)
        assertEquals(512 to 384, thumb.width to thumb.height)
        thumb.recycle() // as compress does
        assertFalse("OCR still reads the full decode", ocr.isRecycled)
        val small = createBitmap(300, 200)
        assertNotSame("a copy even at the same size", small, ImageBytes.scaled(small, 512, 512))

        // A model change re-embeds it the same way.
        assertEquals(512 to 384, size(ImageBytes.jpeg(app, uri, 512)!!))
        assertEquals("no thumbnail from MediaProvider, so none written to shared storage", 0, provider.thumbnailRequests)
    }

    @Test
    fun aScannedPageIsEmbeddedAtTheSizeVisualRendersIt() {
        val page = createBitmap(1600, 2071).apply { eraseColor(Color.WHITE) } // US Letter at OCR_WIDTH
        assertEquals(512 to 663, size(ImageBytes.pageJpeg(page)))
        assertFalse("OCR reads the full render after", page.isRecycled)
    }

    @Test
    fun aTallScreenshotIsDecodedOneOverlappingStripAtATime() {
        val screenshot = createBitmap(540, 9000).applyCanvas {
            drawColor(Color.RED)
            clipRect(0, 4500, 540, 9000)
            drawColor(Color.BLUE)
        }
        val uri = serve("long.png", screenshot, Bitmap.CompressFormat.PNG)
        val strips = ocrStrips(540, 9000)!!

        val seen = mutableListOf<Bitmap>()
        val read = runBlocking {
            ImageBytes.strips(app, uri, strips) {
                seen += it
                Triple(it.width to it.height, it.getPixel(0, 467), it.getPixel(0, 468))
            }!!
        }

        assertEquals(listOf(540 to 4096, 540 to 4096, 540 to 936), read.map { it.first })
        // The second strip starts 64 rows above where the first ends (4032), so the colour change at 4500 is its row 468.
        assertEquals(Color.RED to Color.BLUE, read[1].second to read[1].third)
        assertEquals(Color.BLUE to Color.BLUE, read[2].second to read[2].third)
        assertTrue("only one strip's pixels are held at a time", seen.all { it.isRecycled })

        // A file not the size the plan is for (a JPEG's EXIF rotation) is not read in strips: the caller decodes it whole.
        assertNull(runBlocking { ImageBytes.strips(app, uri, OcrStrips(540, 9001, 1, listOf(0 until 4096))) { it } })
    }

    private companion object {
        const val AUTHORITY = "app.retriever.test.fixtures"
    }
}
