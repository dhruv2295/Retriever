package app.retriever

import app.retriever.core.Kind
import app.retriever.core.SourceFile
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals

class ThumbnailKeyTest {
    private fun file(modifiedAt: Long, size: Long = 10) =
        SourceFile("content://a/1", "a.pdf", "Docs", "application/pdf", Kind.PDF, size, modifiedAt)

    @Test fun editedFileGetsNewKey() {
        val old = Thumbnails.key(file(1), 192)
        assertNotEquals(old, Thumbnails.key(file(2), 192))
        assertNotEquals(old, Thumbnails.key(file(1, size = 11), 192))
        assertEquals(old, Thumbnails.key(file(1), 192))
    }
}
