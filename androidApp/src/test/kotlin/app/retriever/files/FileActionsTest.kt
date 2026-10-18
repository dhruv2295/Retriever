package app.retriever.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileActionsTest {
    @Test fun knownWritableSourcesHaveAllActions() {
        for (uri in listOf("content://media/external/images/media/1", "content://provider/tree/root/document/file", "file:///storage/emulated/0/test.txt")) {
            assertEquals(FileAction.entries, fileActions(uri))
        }
    }

    @Test fun otherProvidersCannotBeRenamedOrDeleted() {
        assertEquals(listOf(FileAction.INFO, FileAction.SHARE, FileAction.OPEN), fileActions("content://provider/item/1"))
    }

    @Test fun namesCannotEscapeTheFolder() {
        for (name in listOf("", " ", ".", "..", "../secret", "a/b", "a\\b", "a\u0000b")) assertFalse(validFileName(name))
        assertTrue(validFileName("Trip 2026.pdf"))
    }
}
