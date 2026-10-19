@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.retriever.core

import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IosExtractorTest {
    private val profile = DeviceProfile(Tier.BALANCED, ModelVariant.M440, Backend.CPU, 70, 5, true, false)
    private val video = SourceFile("ph://video", "video.mp4", "Photos", "video/mp4", Kind.VIDEO, 100, 1)

    private class Frames(private val afterFrame: () -> Unit = {}) : NativeExtractor {
        var frames = 0
        override fun videoDurationMs(uri: String): Long = 120_000
        override fun videoFrameJpeg(uri: String, ms: Long, maxSide: Int): NSData {
            frames++
            afterFrame()
            return byteArrayOf(1).toNSData()
        }
        override fun imageJpeg(uri: String, maxSide: Int): NSData? = error("unexpected image")
        override fun ocr(uri: String, page: Int): String? = error("unexpected OCR")
        override fun pdfPageCount(uri: String): Int = error("unexpected PDF")
        override fun pdfPageText(uri: String, page: Int): String? = error("unexpected PDF")
        override fun pdfPageJpeg(uri: String, page: Int, maxSide: Int): NSData? = error("unexpected PDF")
        override fun textHead(uri: String, maxBytes: Int): String? = error("unexpected text")
        override fun zipEntryNames(uri: String): List<String> = error("unexpected archive")
        override fun zipEntryText(uri: String, name: String, maxBytes: Int): String? = error("unexpected archive")
        override fun readBytes(uri: String, offset: Long, length: Int): NSData? = error("unexpected bytes")
    }

    @Test fun videoReadsFramesWithoutSound() = runBlocking {
        val pieces = IosExtractor(Frames()).extract(video, profile)
        assertTrue(pieces.isNotEmpty())
        assertTrue(pieces.any { p -> p.parts.any { it is Part.Image } })
        assertTrue(pieces.none { p -> p.parts.any { it is Part.Audio } })
    }

    @Test fun audioProvidesNoContent() = runBlocking {
        assertTrue(IosExtractor(Frames()).extract(video.copy(kind = Kind.AUDIO, name = "audio.m4a", mime = "audio/mp4"), profile).isEmpty())
    }

    @Test fun cancellingExtractionStopsBeforeTheNextVideoFrame() = runBlocking {
        val job = Job()
        val native = Frames { job.cancel() }
        assertFailsWith<CancellationException> { withContext(job) { IosExtractor(native).extract(video, profile) } }
        assertEquals(1, native.frames)
    }
}
