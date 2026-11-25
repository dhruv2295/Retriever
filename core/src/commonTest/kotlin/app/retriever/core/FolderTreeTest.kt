package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals

class FolderTreeTest {
    private val counts = listOf(
        "DCIM/Camera" to 40L,
        "DCIM/Screenshots" to 5L,
        "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images" to 7L,
        "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video" to 2L,
        "Download" to 3L,
        "Download/Invoices" to 4L,
        "" to 1L,
    )

    @Test
    fun foldersWithOnlySubfoldersAppearAndSingleChainsJoin() {
        assertEquals(
            listOf(
                FolderNode("", "", 1),
                FolderNode("Android/media/com.whatsapp/WhatsApp/Media", "Android/media/com.whatsapp/WhatsApp/Media", 9),
                FolderNode("DCIM", "DCIM", 45),
                FolderNode("Download", "Download", 7),
            ),
            folderChildren(counts, null),
        )
        // A folder with files of its own is never joined past.
        assertEquals(listOf(FolderNode("Download/Invoices", "Invoices", 4)), folderChildren(counts, "Download"))
        assertEquals(emptyList(), folderChildren(counts, "Download/Invoices"))
    }
}
