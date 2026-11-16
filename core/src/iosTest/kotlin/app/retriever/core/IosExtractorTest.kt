@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.retriever.core

import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosExtractorTest {
    private val profile = DeviceProfile(Tier.BALANCED, ModelVariant.M440, Backend.CPU, 70, 5, true, false)
    private val video = SourceFile("ph://video", "video.mp4", "Photos", "video/mp4", Kind.VIDEO, 100, 1)

    private class Frames(
        private val bytes: ByteArray? = null,
        private val scanned: NativeScan? = null,
        private val image: ByteArray? = null,
        private val afterFrame: () -> Unit = {},
    ) : NativeExtractor {
        var frames = 0
        val scans = mutableListOf<Triple<String, Int, Int>>()
        override fun videoDurationMs(uri: String): Long = 120_000
        override fun videoFrameJpeg(uri: String, ms: Long, maxSide: Int): NSData = error("a frame read for its pixels only")
        override fun videoFrameScan(uri: String, ms: Long, maxSide: Int): NativeScan {
            frames++
            afterFrame()
            return NativeScan(byteArrayOf(1).toNSData(), "", "dog")
        }
        override fun scan(uri: String, page: Int, jpegSide: Int): NativeScan? {
            scans += Triple(uri, page, jpegSide)
            afterFrame()
            return scanned
        }
        override fun imageJpeg(uri: String, maxSide: Int): NSData? {
            afterFrame()
            return image?.toNSData()
        }
        override fun pdfPageCount(uri: String): Int = error("unexpected PDF")
        override fun pdfPageText(uri: String, page: Int): String? = ""
        override fun pdfPageJpeg(uri: String, page: Int, maxSide: Int): NSData? = error("unexpected PDF")
        override fun zipEntryNames(uri: String): List<String> = error("unexpected archive")
        override fun zipEntryText(uri: String, name: String, maxBytes: Int): String? = error("unexpected archive")
        override fun readBytes(uri: String, offset: Long, length: Int): NSData? = checkNotNull(bytes) { "unexpected bytes" }.toNSData()
    }

    private val photo = SourceFile("ph://photo", "IMG_1.HEIC", "Photos", "image/heic", Kind.PHOTO, 0, 1)
    private val receipt = NativeScan(byteArrayOf(7).toNSData(), "Total 1250", "receipt")

    @Test fun aPhotoIsDecodedOnceForItsTextLabelsAndPicture() = runBlocking {
        val native = Frames(scanned = receipt)
        val pieces = IosExtractor(native).extract(photo, profile)
        assertEquals(listOf(Triple(photo.uri, -1, 512)), native.scans)
        val image = pieces.first()
        assertContentEquals(byteArrayOf(7), (image.parts.single() as Part.Image).jpeg)
        assertEquals("receipt", image.labels)
        assertEquals("Total 1250", image.contentText)
    }

    /** With the model here, a photo's first read is its image alone; its words and labels are the scan, read later. */
    @Test fun aPhotoHeadIsItsImageAndItsWordsComeLater() = runBlocking {
        val native = Frames(scanned = receipt, image = byteArrayOf(5))
        val head = IosExtractor(native).extractHead(photo, profile)
        assertEquals(emptyList(), native.scans)
        assertContentEquals(byteArrayOf(5), (head.first().parts.single() as Part.Image).jpeg)
        assertEquals(LATER + WORDS, head.last().locator)
        assertEquals("Total 1250", IosExtractor(native).later(photo, WORDS, profile)?.first()?.contentText)
        assertEquals(listOf(Triple(photo.uri, -1, 512)), native.scans)
    }

    @Test fun aScannedPageIsRenderedOnce() = runBlocking {
        val native = Frames(scanned = NativeScan(byteArrayOf(7).toNSData(), "Total 1250", null))
        val pdf = SourceFile("folder://documents/scan.pdf", "scan.pdf", "Files", "application/pdf", Kind.PDF, 100, 1)
        val pieces = IosExtractor(native).later(pdf, Locator.page(2), profile)
        assertEquals(listOf(Triple(pdf.uri, 1, 1024)), native.scans)
        assertEquals(Locator.page(2), pieces?.first()?.locator)
    }

    /** An iCloud-only photo off Wi-Fi fails its try; a read stopped meanwhile is a stop, which gives the try back. */
    @Test fun aPhotoWithNoImageFailsUnlessTheReadWasStopped() = runBlocking {
        assertFailsWith<IllegalStateException> { IosExtractor(Frames()).extract(photo, profile) }
        val job = Job()
        assertFailsWith<CancellationException> { withContext(job) { IosExtractor(Frames(afterFrame = { job.cancel() })).extract(photo, profile) } }
        Unit
    }

    /** The deepen pass's image read ended by an expiry is a stop too, not a chunk with no image. */
    @Test fun aStoppedImageReadIsAStop() = runBlocking {
        val job = Job()
        var returned = false
        assertFailsWith<CancellationException> {
            withContext(job) {
                IosExtractor(Frames(afterFrame = { job.cancel() })).visual(photo, "")
                returned = true
            }
        }
        assertFalse(returned)
    }

    /** A spreadsheet export saved on Windows: Latin-1 bytes, which UTF-8 would show as replacement characters. */
    @Test fun aLatin1TextFileReadsAsItsText() = runBlocking {
        val csv = SourceFile("file:///prices.csv", "prices.csv", "Files", "text/csv", Kind.DOC, 20, 1)
        val bytes = byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte(), 0x3B, 0x32, 0x80.toByte()) // "café;2€" in Windows-1252
        val text = IosExtractor(Frames(bytes)).extract(csv, profile).joinToString { it.contentText.orEmpty() }
        assertEquals("café;2€", text)
    }

    @Test fun videoReadsFramesWithoutSound() = runBlocking {
        val pieces = IosExtractor(Frames()).extract(video, profile)
        assertTrue(pieces.isNotEmpty())
        assertTrue(pieces.any { p -> p.parts.any { it is Part.Image } })
        assertTrue(pieces.none { p -> p.parts.any { it is Part.Audio } })
    }

    @Test fun videoFramesCarryTheirLabelsNowAndLater() = runBlocking {
        val extractor = IosExtractor(Frames())
        val pieces = extractor.extractHead(video, profile)
        assertEquals("dog", pieces.single { it.parts.isNotEmpty() }.labels)
        val later = pieces.first { it.locator.startsWith(LATER) }.locator.removePrefix(LATER)
        assertEquals(listOf("dog"), extractor.later(video, later, profile)?.map { it.labels })
    }

    @Test fun audioProvidesNoContent() = runBlocking {
        assertTrue(IosExtractor(Frames()).extract(video.copy(kind = Kind.AUDIO, name = "audio.m4a", mime = "audio/mp4"), profile).isEmpty())
    }

    @Test fun cancellingExtractionStopsBeforeTheNextVideoFrame() = runBlocking {
        val job = Job()
        val native = Frames(afterFrame = { job.cancel() })
        assertFailsWith<CancellationException> { withContext(job) { IosExtractor(native).extract(video, profile) } }
        assertEquals(1, native.frames)
    }
}
