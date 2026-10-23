package app.retriever.ui.components

import app.retriever.core.Kind
import app.retriever.core.SourceFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ThumbnailKeyTest {
    private fun file(modifiedAt: Long, size: Long = 10) = SourceFile("content://a/1", "a.pdf", "Docs", "application/pdf", Kind.PDF, size, modifiedAt)

    @Test fun editedFileGetsNewKey() {
        val old = Thumbnails.key(file(1), 192)
        assertNotEquals(old, Thumbnails.key(file(2), 192))
        assertNotEquals(old, Thumbnails.key(file(1, size = 11), 192))
        assertEquals(old, Thumbnails.key(file(1), 192))
    }

    @Test fun onlyPdfPagesQueueOnThePdfDispatcher() {
        assertSame(Thumbnails.pdfDispatcher, Thumbnails.dispatcherFor(Kind.PDF))
        for (kind in listOf(Kind.PHOTO, Kind.SCREENSHOT, Kind.VIDEO, Kind.DOC, Kind.AUDIO)) {
            assertNotSame(kind.name, Thumbnails.pdfDispatcher, Thumbnails.dispatcherFor(kind))
            assertNotSame(kind.name, Thumbnails.renderDispatcher, Thumbnails.dispatcherFor(kind))
        }
    }

    @Test fun requestSizesRoundUpToABucket() {
        assertEquals(listOf(160, 160, 256, 384, 512, 768, 768, 768), listOf(100, 160, 161, 257, 449, 661, 768, 2_000).map(Thumbnails::bucket))
    }
}
