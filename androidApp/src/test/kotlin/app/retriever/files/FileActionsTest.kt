package app.retriever.files

import app.retriever.core.Kind
import app.retriever.core.SourceFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileActionsTest {
    private val writable =
        listOf("content://media/external/images/media/1", "content://provider/tree/root/document/file", "file:///storage/emulated/0/test.txt")
    private val pdf = SourceFile("file:///a.pdf", "a.pdf", "Documents", "application/pdf", Kind.PDF, 2_400_000, 1_760_000_000_000)

    @Test fun knownWritableSourcesHaveAllActionsInTheMenuOrder() {
        for (uri in writable) {
            assertEquals(listOf(FileAction.STAR, FileAction.SHARE, FileAction.OPEN, FileAction.INFO, FileAction.RENAME, FileAction.DELETE), fileActions(uri))
        }
    }

    @Test fun otherProvidersCannotBeRenamedOrDeleted() {
        assertEquals(listOf(FileAction.STAR, FileAction.SHARE, FileAction.OPEN, FileAction.INFO), fileActions("content://provider/item/1"))
    }

    @Test fun theStarItemNamesWhatItWillDo() {
        assertEquals("Add to Starred", FileAction.STAR.label(false))
        assertEquals("Remove from Starred", FileAction.STAR.label(true))
        assertEquals("Open in another app", FileAction.OPEN.label(true))
        assertEquals("File info", FileAction.INFO.label(false))
    }

    @Test fun namesCannotEscapeTheFolder() {
        for (name in listOf("", " ", ".", "..", "../secret", "a/b", "a\\b", "a\u0000b")) assertFalse(validFileName(name))
        assertTrue(validFileName("Trip 2026.pdf"))
    }

    @Test fun theFolderIsNamedByItsLastPartAndTheRootIsTopLevel() {
        assertEquals("Camera", friendlyFolder("DCIM/Camera"))
        assertEquals("Documents", friendlyFolder("Documents"))
        assertEquals("Top level", friendlyFolder(""))
    }

    @Test fun theInfoRowsShowTakenOnlyWhenKnownAndEndWithReading() {
        val rows = infoRows(pdf, listOf("Pages" to "12"), "Read")
        assertEquals(listOf("Modified", "Pages", "Found by"), rows.map { it.first })
        val taken = infoRows(pdf.copy(takenAt = 1_700_000_000_000), emptyList(), "Waiting to read")
        assertEquals(listOf("Modified", "Taken", "Found by"), taken.map { it.first })
        assertEquals("PDF · 2.4\u00A0MB", kindAndSize(pdf))
    }
}
