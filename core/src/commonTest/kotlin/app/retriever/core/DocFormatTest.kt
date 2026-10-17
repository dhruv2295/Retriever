package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DocFormatTest {
    @Test
    fun eachFormatByExtensionOrMime() {
        val cases = listOf(
            Triple("a.DOCX", "application/octet-stream", DocFormat.DOCX),
            Triple("a", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", DocFormat.DOCX),
            Triple("deck.pptx", "", DocFormat.PPTX),
            Triple("deck", "application/vnd.openxmlformats-officedocument.presentationml.presentation", DocFormat.PPTX),
            Triple("sheet.xlsx", "", DocFormat.XLSX),
            Triple("sheet", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", DocFormat.XLSX),
            Triple("notes.odt", "", DocFormat.ODF),
            Triple("calc.ods", "", DocFormat.ODF),
            Triple("talk.odp", "", DocFormat.ODF),
            Triple("x", "application/vnd.oasis.opendocument.text", DocFormat.ODF),
            Triple("book.epub", "", DocFormat.EPUB),
            Triple("book", "application/epub+zip", DocFormat.EPUB),
            Triple("page.html", "", DocFormat.HTML),
            Triple("page.htm", "", DocFormat.HTML),
            Triple("page", "text/html", DocFormat.HTML),
            Triple("notes.md", "", DocFormat.TEXT),
            Triple("main.kt", "application/octet-stream", DocFormat.TEXT),
            Triple("readme", "text/plain", DocFormat.TEXT),
            Triple("data", "application/json", DocFormat.TEXT),
            // RTF is text/* to MediaStore and UTType, so it must win before the text rule indexes its control words.
            Triple("letter.rtf", "text/rtf", DocFormat.RTF),
            Triple("letter.RTF", "", DocFormat.RTF),
            Triple("letter", "application/rtf", DocFormat.RTF),
        )
        for ((name, mime, want) in cases) assertEquals(want, docFormat(name, mime), "$name $mime")
    }

    @Test
    fun unreadableIsNull() {
        assertNull(docFormat("archive.zip", "application/zip"))
        assertNull(docFormat("noext", ""))
        // Word 97-2003: UIKit's NSAttributedString reads only plain, RTF, RTFD and HTML, so .doc stays name-only.
        assertNull(docFormat("old.doc", "application/msword"))
    }

    @Test
    fun singleEntryFormatsNameTheirEntry() {
        assertEquals("word/document.xml", DocFormat.DOCX.textEntry)
        assertEquals("xl/sharedStrings.xml", DocFormat.XLSX.textEntry)
        assertEquals("content.xml", DocFormat.ODF.textEntry)
        assertNull(DocFormat.PPTX.textEntry)
    }
}
