package app.retriever.core

import app.retriever.core.android.zipText
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The zip reader behind docx, pptx, xlsx and epub: budget, shared strings and early stop. */
class ZipTextTest {
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            entries.forEach { (name, bytes) ->
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private class Counting(input: InputStream) : FilterInputStream(input) {
        var consumed = 0
        override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, len).also { if (it > 0) consumed += it }
    }

    @Test
    fun sharedStringsResolveEvenWhenAHugeSheetComesFirst() {
        val sheet = "<worksheet><sheetData><row><c t=\"s\"><v>0</v></c></row>" + "<row><c><v>1</v></c></row>".repeat(300_000) + "</sheetData></worksheet>"
        assertTrue(sheet.length > 3 * TEXT_BYTES)
        val strings = "<sst><si><t>quokka</t></si></sst>"
        val bytes = zip("xl/worksheets/sheet1.xml" to sheet.toByteArray(), "xl/sharedStrings.xml" to strings.toByteArray())
        var cuts = 0
        val raw = runBlocking { zipText(ByteArrayInputStream(bytes), ::xlsxOrder, { cuts++ }, { it }, xlsx = true) }
        assertTrue(xlsxText(raw).startsWith("quokka\n"))
        assertTrue(cuts > 0, "the oversized sheet is recorded as cut")
    }

    @Test
    fun aDocxStopsReadingAfterItsTextEntry() {
        val bytes = zip(
            "word/document.xml" to "<w:p><w:t>hello</w:t></w:p>".toByteArray(),
            "word/media/image1.bin" to Random(1).nextBytes(3 * 1024 * 1024),
        )
        val counted = Counting(ByteArrayInputStream(bytes))
        val text = runBlocking { zipText(counted, { if (it == "word/document.xml") 0 else null }, {}, single = true) }
        assertEquals("hello", text.trim())
        assertTrue(counted.consumed < 64 * 1024, "${counted.consumed} of ${bytes.size} bytes")
    }

    @Test
    fun theTotalBudgetStillCapsManyEntries() {
        val entry = "a".repeat(TEXT_BYTES).toByteArray()
        val bytes = zip(*Array(20) { "c$it.html" to entry })
        var cuts = 0
        val text = runBlocking { zipText(ByteArrayInputStream(bytes), { 0 }, { cuts++ }, { it }) }
        assertTrue(text.count { it == 'a' } <= 4 * TEXT_BYTES)
        assertTrue(cuts > 0)
    }
}
