package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileRulesTest {
    @Test
    fun classifiesByMimePathAndName() {
        assertEquals(Kind.SCREENSHOT, FileRules.classify("image/png", "Pictures/Screenshots/", "a.png"))
        assertEquals(Kind.PHOTO, FileRules.classify("image/jpeg", "DCIM/Camera/", "b.jpg"))
        assertEquals(Kind.PDF, FileRules.classify("application/pdf", "Download/", "c.pdf"))
        assertEquals(Kind.DOC, FileRules.classify("text/csv", "Download/", "d.csv"))
        assertEquals(
            Kind.DOC,
            FileRules.classify(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Download/", "e.docx",
            ),
        )
        assertEquals(Kind.DOC, FileRules.classify("application/octet-stream", "Download/", "f.odt"))
        assertEquals(Kind.DOC, FileRules.classify("application/epub+zip", "Books/", "g.epub"))
        assertEquals(Kind.DOC, FileRules.classify("application/octet-stream", "Download/", "h.html"))
        assertEquals(Kind.OTHER, FileRules.classify("application/octet-stream", "Download/", "i.bin"))
        assertEquals(Kind.DOC, FileRules.classify("application/rtf", "Download/", "k.rtf"))
        assertEquals(Kind.DOC, FileRules.classify("application/octet-stream", "Download/", "l.rtf"))
        assertEquals(Kind.DOC, FileRules.classify(FileRules.mimeForExtension("rtf")!!, "Download/", "m.rtf"))
    }

    @Test
    fun nullMimeFallsBackToExtension() {
        val mime = FileRules.mimeForExtension("md")!!
        assertEquals(Kind.DOC, FileRules.classify(mime, "Notes/", "j.md"))
        assertEquals(null, FileRules.mimeForExtension("zzz"))
    }

    @Test
    fun skipsPrivateAndHiddenButKeepsAndroidMedia() {
        assertTrue(FileRules.shouldSkip(".thumbnails/", "x.jpg"))
        assertTrue(FileRules.shouldSkip("Android/data/com.x/", "y.pdf"))
        assertTrue(FileRules.shouldSkip("Pictures/", ".nomedia"))
        assertFalse(FileRules.shouldSkip("Android/media/com.whatsapp/", "z.jpg"))
    }

    @Test
    fun pickSamplesReturnsNewestPerKind() {
        data class F(val kind: Kind, val at: Long)
        val files = listOf(F(Kind.PHOTO, 1), F(Kind.PHOTO, 3), F(Kind.PHOTO, 2), F(Kind.DOC, 5), F(Kind.VIDEO, 9))
        val picked = FileRules.pickSamples(files, mapOf(Kind.PHOTO to 2, Kind.DOC to 1), { it.kind }, { it.at })
        assertEquals(setOf(F(Kind.PHOTO, 3), F(Kind.PHOTO, 2), F(Kind.DOC, 5)), picked.toSet())
        assertEquals(3, picked.size)
    }
}
