package app.retriever

import app.retriever.core.Kind
import app.retriever.core.SearchEngine
import app.retriever.core.SourceFile
import app.retriever.core.TEXT_BYTES
import app.retriever.core.android.readCapped
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class ReadingRulesTest {
    /** Hands out at most 7 bytes a read, as a slow provider might. */
    private class Trickle(private val bytes: ByteArray) : InputStream() {
        private var at = 0
        override fun read(): Int = if (at < bytes.size) bytes[at++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (at >= bytes.size) return -1
            val n = minOf(len, 7, bytes.size - at)
            System.arraycopy(bytes, at, b, off, n)
            at += n
            return n
        }
    }

    @Test fun aTextFileIsReadToItsCapAndSaysWhenItWasCut() {
        var cut = false
        val over = ByteArrayInputStream(ByteArray(TEXT_BYTES + 1) { 'a'.code.toByte() }).readCapped(TEXT_BYTES) { cut = true }
        assertEquals(TEXT_BYTES, over.length)
        assertTrue(cut)

        cut = false
        assertEquals(TEXT_BYTES, ByteArrayInputStream(ByteArray(TEXT_BYTES) { 'b'.code.toByte() }).readCapped(TEXT_BYTES) { cut = true }.length)
        assertFalse("exactly the cap is read whole", cut)
    }

    @Test fun shortReadsAddUp() {
        var cut = false
        assertEquals("hello, small file", Trickle("hello, small file".toByteArray()).readCapped(1000) { cut = true })
        assertFalse(cut)
        assertEquals("hello", Trickle("hello, small file".toByteArray()).readCapped(5) { cut = true })
        assertTrue(cut)
    }

    private fun file(uri: String, name: String, folder: String, modifiedAt: Long = 1_700_000_000_000) =
        SourceFile(uri, name, folder, "image/jpeg", Kind.PHOTO, 300_000, modifiedAt)

    @Test fun oneFileListedByAFolderAndByMediaStoreIsNotACopy() {
        // A memory card's photo: MediaStore names the volume one way, the picked folder another, and keeps whole seconds.
        val media = file("content://media/external/images/media/12", "IMG_1.jpg", "SanDisk SD card/DCIM/Camera", 1_700_000_000_000)
        val saf = file("content://com.android.externalstorage.documents/tree/1A2B-3C4D%3ADCIM/document/1A2B-3C4D%3ADCIM%2FCamera%2FIMG_1.jpg", "IMG_1.jpg", "SD card/DCIM/Camera", 1_700_000_000_123)
        assertEquals(listOf(media), oneEntryPerFile(listOf(media, saf)))
        // A real copy elsewhere, or with another time, stays.
        val copy = file("content://media/external/images/media/40", "IMG_1.jpg", "Download", 1_700_000_000_000)
        assertEquals(listOf(media, copy), oneEntryPerFile(listOf(media, copy)))
        val later = saf.copy(modifiedAt = 1_800_000_000_000)
        assertEquals(listOf(media, later), oneEntryPerFile(listOf(media, later)))
    }

    private fun hit(name: String, kind: Kind) = SearchEngine.Hit(file("content://x/$name", name, "").copy(kind = kind), name.hashCode().toLong(), 1, "", null, 1.0)

    @Test fun onlyTheDocumentsRankedAboveTheBestPictureComeBeforeThePictures() {
        val lease = hit("Lease.pdf", Kind.PDF)
        val notes = hit("notes.txt", Kind.DOC)
        val dog = hit("IMG_2.jpg", Kind.PHOTO)
        val cat = hit("IMG_3.jpg", Kind.PHOTO)
        // One strong document no longer pushes every photo below every weaker document.
        assertEquals(Triple(listOf(lease), listOf(dog, cat), listOf(notes)), sectionsOf(listOf(lease, dog, notes, cat)))
        val none = emptyList<SearchEngine.Hit>()
        assertEquals(Triple(none, listOf(dog), listOf(lease)), sectionsOf(listOf(dog, lease)))
        assertEquals(Triple(listOf(lease, notes), none, none), sectionsOf(listOf(lease, notes)))
    }
}
