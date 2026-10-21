package app.retriever.ui.components

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.net.toUri
import app.retriever.ShotTheme
import app.retriever.core.Kind
import app.retriever.core.SourceFile
import app.retriever.shot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/** Documents' tiles drawn from their own head: each reader, each page at Try's grid and the pile, and the fast fallbacks. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class DocPageTest {
    @get:Rule val rule = createComposeRule()

    private fun read(name: String, bytes: ByteArray, mime: String = "application/octet-stream") = DocPage.read(ByteArrayInputStream(bytes), name, mime)

    private fun rows(name: String, bytes: ByteArray, mime: String = "application/octet-stream") = (read(name, bytes, mime) as Head.Page).rows

    private fun cells(name: String, bytes: ByteArray) = rows(name, bytes).map { it.single() }

    @Test fun docxReadsItsParagraphsWithoutFieldCodes() {
        assertEquals(Look.PROSE, (read("Letter.docx", Fixtures.docx) as Head.Page).look)
        assertEquals(listOf("Quarterly letter", "Dear reader,", "Thank you for the update on the garden project."), cells("Letter.docx", Fixtures.docx))
    }

    @Test fun aMacroVariantIsKnownByItsContentNotItsName() {
        assertEquals("Quarterly letter", cells("Report.docm", Fixtures.docx).first())
    }

    @Test fun xlsxKeepsEachCellInItsColumn() {
        assertEquals(Look.GRID, (read("Budget.xlsx", Fixtures.xlsx) as Head.Page).look)
        assertEquals(listOf(listOf("Item", "Amount", ""), listOf("Rent", "1200", ""), listOf("Coffee", "", "0.3")), rows("Budget.xlsx", Fixtures.xlsx))
    }

    @Test fun aBigWorkbookKeepsItsSharedStrings() {
        val (rows, took) = measureTimedValue { rows("Big.xlsx", Fixtures.bigXlsx) }
        println("DOCPAGE big xlsx: ${took.inWholeMicroseconds / 1000.0} ms")
        assertEquals(listOf(listOf("Item", "Amount"), listOf("Rent", "1200")), rows.take(2))
        assertTrue("big xlsx took $took", took < 2.seconds)
    }

    @Test fun pptxReadsSlideOneOnly() {
        assertEquals(Look.SLIDE, (read("Deck.pptx", Fixtures.pptx) as Head.Page).look)
        assertEquals(listOf("Garden plan", "Plant in spring", "Water weekly"), cells("Deck.pptx", Fixtures.pptx))
    }

    @Test fun odtReadsItsBody() {
        assertEquals(listOf("Meeting notes", "Bring the map", "Lunch at noon"), cells("Notes.odt", Fixtures.odt))
    }

    @Test fun odsHonoursRepeatedCellsAndDropsTheEmptyTail() {
        assertEquals(listOf(listOf("Day", "Steps"), listOf("Monday", "4000"), listOf("Tuesday", "")), rows("Steps.ods", Fixtures.ods))
    }

    @Test fun odpReadsSlideOneWithoutItsNotes() {
        assertEquals(Look.SLIDE, (read("Trip.odp", Fixtures.odp) as Head.Page).look)
        assertEquals(listOf("Trip overview", "Day one: museum"), cells("Trip.odp", Fixtures.odp))
    }

    @Test fun anEmbeddedThumbnailWinsAndIsDrawnAsThePage() {
        val head = read("Letter.docx", Fixtures.withPicture)
        assertTrue(head is Head.Picture)
        val bitmap = DocPage.draw(head!!, 256)!!
        val centre = bitmap[128, 128]
        assertTrue("centre ${Integer.toHexString(centre)}", android.graphics.Color.red(centre) > 200 && android.graphics.Color.green(centre) < 60)
        // Around the page the tile's own colour shows.
        assertEquals(0, android.graphics.Color.alpha(bitmap[2, 2]))
        assertTrue(read("Notes.odt", Fixtures.odtWithPicture) is Head.Picture)
    }

    @Test fun aMetafileThumbnailIsSkippedForTheDrawnPage() {
        assertEquals("Quarterly letter", cells("Letter.docx", Fixtures.withMetafile).first())
    }

    @Test fun aBrokenThumbnailLeavesTheDrawnPage() {
        assertEquals("Quarterly letter", cells("Letter.docx", Fixtures.brokenPicture).first())
    }

    @Test fun aThumbnailPastTheBudgetLeavesTheDrawnPage() {
        assertEquals("Garden plan", cells("Deck.pptx", Fixtures.mediaBeforePicture).first())
    }

    @Test fun csvAndTsvAreGrids() {
        val csv = "Name,\"Note, with comma\",Total\nAlpha,\"say \"\"hi\"\"\",3\n".encodeToByteArray()
        assertEquals(listOf(listOf("Name", "Note, with comma", "Total"), listOf("Alpha", "say \"hi\"", "3")), rows("Expenses.csv", csv))
        assertEquals(listOf(listOf("City", "Visits"), listOf("Lisbon", "2")), rows("Trips.tsv", "City\tVisits\nLisbon\t2\n".encodeToByteArray()))
    }

    @Test fun textFilesPickProseOrMonospace() {
        assertEquals(Look.PROSE, (read("Notes.txt", Fixtures.txt) as Head.Page).look)
        assertEquals(Look.PROSE, (read("Readme.md", "# Plants\n\nWater on Sundays.".encodeToByteArray()) as Head.Page).look)
        for (name in listOf("Settings.json", "Feed.xml", "Config.yaml", "Main.kt")) {
            assertEquals(name, Look.CODE, (read(name, Fixtures.text.getValue(name)) as Head.Page).look)
        }
        // Code keeps its indentation; tabs become spaces.
        assertEquals("    println(\"hello\")", cells("Main.kt", Fixtures.text.getValue("Main.kt"))[1])
    }

    @Test fun markupStartsAtItsFirstElement() {
        val xml = "<?xml version=\"1.0\"?>\n<!-- generated -->\n<?xml-stylesheet href=\"a.xsl\"?>\n" +
            "<!DOCTYPE feed [\n<!ENTITY a \"b\">\n]>\n\n<feed>\n\t<title>Garden news</title>\n</feed>"
        assertEquals(listOf("<feed>", "    <title>Garden news</title>", "</feed>"), cells("Feed.xml", xml.encodeToByteArray()))
    }

    @Test fun aColouredLogShowsItsTextWithoutEscapes() {
        val log = "\u001B[32mINFO\u001B[0m build started\n\u000C\n\u001B[31mERROR\u001B[0m step failed".encodeToByteArray()
        assertEquals(listOf("INFO build started", "", "", "ERROR step failed"), cells("Build.log", log))
    }

    @Test fun htmlShowsItsBodyTextAndNoCssOrScript() {
        assertEquals(listOf("Welcome", "Opening hours"), cells("Page.html", Fixtures.html))
    }

    @Test fun rtfShowsItsText() {
        assertEquals(listOf("Hello world", "Second line"), cells("Letter.rtf", Fixtures.rtf))
    }

    @Test fun legacyTextDecodesWithoutReplacementCharacters() {
        val cp1252 = byteArrayOf(0x43, 0x61, 0x66, 0xE9.toByte(), 0x20, 0x6D, 0x65, 0x6E, 0x75)
        assertEquals(listOf("Café menu"), cells("Menu.txt", cp1252))
    }

    @Test fun binaryNamedAsTextShowsNoText() {
        assertNull(read("Notes.txt", ByteArray(256) { it.toByte() }))
        // An encrypted Office file is an OLE2 container, not a zip.
        assertNull(read("Letter.docx", byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte()) + ByteArray(512)))
        assertNull(read("Archive.zip", Fixtures.zip("readme.txt" to "hello".encodeToByteArray())))
        assertNull(read("Empty.txt", ByteArray(0)))
    }

    @Test fun corruptOrHugeFilesFallBackFast() {
        val random = Random(7).nextBytes(2 shl 20)
        val cases = listOf(
            "random bytes as docx" to random,
            "zip signature then garbage" to byteArrayOf(0x50, 0x4B, 0x03, 0x04) + random,
            "64 MB of zeros ahead of the text" to Fixtures.bomb,
        )
        for ((label, bytes) in cases) {
            val (head, took) = measureTimedValue { read("Letter.docx", bytes) }
            println("DOCPAGE fallback $label: ${took.inWholeMicroseconds / 1000.0} ms")
            assertNull(label, head)
            assertTrue("$label took $took", took < 2.seconds)
        }
        // 64 MB of text with no end in sight: only the head is read.
        val huge = object : InputStream() {
            var left = 64L shl 20

            override fun read(): Int = if (left-- <= 0) {
                -1
            } else if (left % 48 == 0L) {
                '\n'.code
            } else {
                'a'.code
            }
        }
        val (head, took) = measureTimedValue { DocPage.read(huge, "Server.log", "text/plain") }
        println("DOCPAGE huge text: ${took.inWholeMicroseconds / 1000.0} ms")
        assertEquals(Look.CODE, (head as Head.Page).look)
        assertTrue("huge text took $took", took < 2.seconds)
    }

    @Test fun everyTypeReadsAndDrawsQuickly() {
        for ((name, bytes) in Fixtures.all - "Broken.docx") {
            for (n in listOf(256, 512)) {
                val (bitmap, took) = measureTimedValue { read(name, bytes)?.let { DocPage.draw(it, n) } }
                println("DOCPAGE timing $name @$n px: ${took.inWholeMicroseconds / 1000.0} ms")
                assertNotNull(name, bitmap)
                assertEquals(n, bitmap!!.width)
                assertTrue("$name took $took", took < 1.seconds)
            }
        }
    }

    @Test fun noDecodeRunsOnTheMainThread() {
        val main = Looper.getMainLooper().thread
        assertSame("the test drives load from the main thread", main, Thread.currentThread())
        var openedOn: Thread? = null
        val file = register("main-thread/Letter.docx", Fixtures.docx) { openedOn = Thread.currentThread() }
        val bitmap = runBlocking { Thumbnails.load(RuntimeEnvironment.getApplication(), file, 256) }
        assertNotNull(bitmap)
        assertNotNull(openedOn)
        assertNotSame(main, openedOn)
    }

    @Test fun aCorruptFileKeepsItsTypeCard() {
        val file = register("corrupt/Letter.docx", byteArrayOf(0x50, 0x4B, 0x03, 0x04) + Random(3).nextBytes(4096))
        assertNull(runBlocking { Thumbnails.load(RuntimeEnvironment.getApplication(), file, 256) })
    }

    private fun register(path: String, bytes: ByteArray, onOpen: () -> Unit = {}): SourceFile {
        val uri = "content://docpage.test/$path"
        shadowOf(RuntimeEnvironment.getApplication().contentResolver).registerInputStreamSupplier(uri.toUri()) {
            onOpen()
            ByteArrayInputStream(bytes)
        }
        val kind = if (path.endsWith(".docm")) Kind.OTHER else Kind.DOC
        return SourceFile(uri, path.substringAfterLast('/'), "Documents", "application/octet-stream", kind, bytes.size.toLong(), 1_760_000_000_000)
    }

    // --- Renders: each type at Try's grid (84 dp, 256 px) and the pile (168 dp, 512 px), light over dark. ---

    @Test fun docx() = shot("docx", "Letter.docx")

    @Test fun docm() = shot("docm", "Report.docm")

    @Test fun xlsx() = shot("xlsx", "Budget.xlsx")

    @Test fun pptx() = shot("pptx", "Deck.pptx")

    @Test fun odt() = shot("odt", "Notes.odt")

    @Test fun ods() = shot("ods", "Steps.ods")

    @Test fun odp() = shot("odp", "Trip.odp")

    @Test fun thumbnail() = shot("thumbnail", "Pictured.docx")

    @Test fun csv() = shot("csv", "Expenses.csv")

    @Test fun txt() = shot("txt", "Notes.txt")

    @Test fun md() = shot("md", "Readme.md")

    @Test fun json() = shot("json", "Settings.json")

    @Test fun xml() = shot("xml", "Feed.xml")

    @Test fun yaml() = shot("yaml", "Config.yaml")

    @Test fun html() = shot("html", "Page.html")

    @Test fun code() = shot("code", "Main.kt")

    @Test fun rtf() = shot("rtf", "Letter.rtf")

    /** No head to draw: the type card stays. */
    @Test fun fallback() = shot("fallback", "Broken.docx")

    private fun shot(type: String, name: String) {
        val file = register("shot/$name", Fixtures.all.getValue(name))
        rule.setContent {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                for (dark in listOf(false, true)) {
                    ShotTheme(dark) {
                        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FileTile(file, 84.dp)
                            FileTile(file, 168.dp)
                        }
                    }
                }
            }
        }
        if (type != "fallback") rule.waitUntil(10_000) { Thumbnails.cached(file, 256) != null && Thumbnails.cached(file, 512) != null }
        rule.waitForIdle()
        rule.shot("docpage_$type")
    }
}

/** Generated files with generic names and content. */
private object Fixtures {
    fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
    }.toByteArray()

    private fun s(text: String) = text.trimIndent().encodeToByteArray()

    private val types = "[Content_Types].xml" to s("""<?xml version="1.0"?><Types/>""")

    private val documentXml = s(
        """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
        <w:p><w:pPr><w:pStyle w:val="Title"/></w:pPr><w:r><w:t>Quarterly letter</w:t></w:r></w:p>
        <w:p><w:r><w:t>Dear reader,</w:t></w:r></w:p>
        <w:p><w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText xml:space="preserve"> PAGE </w:instrText></w:r></w:p>
        <w:p><w:r><w:t>Thank you for the update on the garden project.</w:t></w:r></w:p>
        </w:body></w:document>
        """,
    )

    val docx = zip(types, "word/document.xml" to documentXml)

    private fun picture(color: Int, format: Bitmap.CompressFormat): ByteArray {
        val bitmap = createBitmap(90, 128).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(format, 90, it) }.toByteArray()
    }

    val withPicture by lazy {
        zip(
            types,
            "docProps/thumbnail.jpeg" to picture(android.graphics.Color.RED, Bitmap.CompressFormat.JPEG),
            "word/document.xml" to documentXml,
        )
    }

    /** Word stores the thumbnail after the document; this one has no image in it. */
    val brokenPicture = zip(types, "word/document.xml" to documentXml, "docProps/thumbnail.jpeg" to Random(9).nextBytes(4096))

    val withMetafile = zip(types, "docProps/thumbnail.emf" to ByteArray(64) { 1 }, "word/document.xml" to documentXml)

    val xlsx = zip(
        types,
        // Excel writes the sheet before the shared strings.
        "xl/worksheets/sheet1.xml" to s(
            """
            <worksheet><sheetData>
            <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
            <row r="2"><c r="A2" t="s"><v>2</v></c><c r="B2"><v>1200</v></c></row>
            <row r="3"><c r="A3" t="s"><v>3</v></c><c r="C3"><v>0.30000000000000004</v></c></row>
            <row r="4"/>
            </sheetData></worksheet>
            """,
        ),
        "xl/sharedStrings.xml" to s("<sst><si><t>Item</t></si><si><t>Amount</t></si><si><t>Rent</t></si><si><r><t>Cof</t></r><r><t>fee</t></r></si></sst>"),
    )

    /** About 5 MB of sheet ahead of its shared strings, as a big Excel export stores them. */
    val bigXlsx by lazy {
        val sheet = StringBuilder("<worksheet><sheetData>")
        sheet.append("<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row>")
        sheet.append("<row r=\"2\"><c r=\"A2\" t=\"s\"><v>2</v></c><c r=\"B2\"><v>1200</v></c></row>")
        for (r in 3..60_000) sheet.append("<row r=\"$r\"><c r=\"A$r\"><v>$r</v></c><c r=\"B$r\"><v>${r * 2}</v></c></row>")
        sheet.append("</sheetData></worksheet>")
        zip(
            types,
            "xl/worksheets/sheet1.xml" to sheet.toString().encodeToByteArray(),
            "xl/sharedStrings.xml" to s("<sst><si><t>Item</t></si><si><t>Amount</t></si><si><t>Rent</t></si></sst>"),
        )
    }

    private val slide1 = s(
        """
        <p:sld><p:cSld><p:spTree>
        <p:sp><p:nvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:txBody><a:p><a:r><a:t>Garden plan</a:t></a:r></a:p></p:txBody></p:sp>
        <p:sp><p:txBody><a:p><a:r><a:t>Plant in spring</a:t></a:r></a:p><a:p><a:r><a:t>Water weekly</a:t></a:r></a:p></p:txBody></p:sp>
        </p:spTree></p:cSld></p:sld>
        """,
    )

    val pptx = zip(types, "ppt/slides/slide1.xml" to slide1, "ppt/slides/slide2.xml" to s("<p:sld><a:p><a:r><a:t>Second slide</a:t></a:r></a:p></p:sld>"))

    /** A deck whose media comes before its thumbnail, past the pass's budget: the drawn slide shows. */
    val mediaBeforePicture by lazy {
        zip(
            types,
            "ppt/slides/slide1.xml" to slide1,
            "ppt/media/video1.mp4" to ByteArray(6 shl 20),
            "docProps/thumbnail.jpeg" to picture(android.graphics.Color.RED, Bitmap.CompressFormat.JPEG),
        )
    }

    /** A zip bomb's shape: 64 MB of zeros (64 KB deflated) before the document. */
    val bomb by lazy { zip(types, "word/media/blank.bin" to ByteArray(64 shl 20), "word/document.xml" to documentXml) }

    private fun odf(mime: String, body: String) = zip(
        "mimetype" to mime.encodeToByteArray(),
        "content.xml" to s(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <office:document-content><office:automatic-styles><style:style style:name="P1"/></office:automatic-styles>
            <office:body>$body</office:body></office:document-content>
            """,
        ),
    )

    val odt = odf(
        "application/vnd.oasis.opendocument.text",
        "<office:text><text:h>Meeting notes</text:h><text:p>Bring the map</text:p><text:p/><text:p>Lunch at noon</text:p></office:text>",
    )

    val odtWithPicture by lazy {
        zip(
            "mimetype" to "application/vnd.oasis.opendocument.text".encodeToByteArray(),
            "Thumbnails/thumbnail.png" to picture(android.graphics.Color.BLUE, Bitmap.CompressFormat.PNG),
            "content.xml" to
                s("<office:document-content><office:body><office:text><text:p>Hello</text:p></office:text></office:body></office:document-content>"),
        )
    }

    val ods = odf(
        "application/vnd.oasis.opendocument.spreadsheet",
        """
        <office:spreadsheet><table:table table:name="Sheet1">
        <table:table-column table:number-columns-repeated="3"/>
        <table:table-row><table:table-cell><text:p>Day</text:p></table:table-cell><table:table-cell><text:p>Steps</text:p></table:table-cell><table:table-cell table:number-columns-repeated="1020"/></table:table-row>
        <table:table-row><table:table-cell><text:p>Monday</text:p></table:table-cell><table:table-cell><text:p>4000</text:p></table:table-cell></table:table-row>
        <table:table-row><table:table-cell><text:p>Tuesday</text:p></table:table-cell><table:table-cell table:number-columns-repeated="2"/></table:table-row>
        <table:table-row table:number-rows-repeated="1048573"><table:table-cell table:number-columns-repeated="1024"/></table:table-row>
        </table:table><table:table table:name="Sheet2"><table:table-row><table:table-cell><text:p>Other</text:p></table:table-cell></table:table-row></table:table>
        </office:spreadsheet>
        """,
    )

    val odp = odf(
        "application/vnd.oasis.opendocument.presentation",
        """
        <office:presentation>
        <draw:page draw:name="page1"><draw:frame><draw:text-box><text:p>Trip overview</text:p></draw:text-box></draw:frame>
        <draw:frame><draw:text-box><text:p>Day one: museum</text:p></draw:text-box></draw:frame>
        <presentation:notes><draw:frame><draw:text-box><text:p>Speaker reminder</text:p></draw:text-box></draw:frame></presentation:notes></draw:page>
        <draw:page draw:name="page2"><draw:frame><draw:text-box><text:p>Second slide</text:p></draw:text-box></draw:frame></draw:page>
        </office:presentation>
        """,
    )

    val txt = s(
        """
        Shopping list for the weekend

        Bread, milk and a dozen eggs from the market. Pick up the parcel at the post office before noon on Saturday.
        Call about the bicycle repair.
        """,
    )

    val text = mapOf(
        "Settings.json" to "{\n  \"theme\": \"light\",\n  \"fontSize\": 14,\n  \"sync\": true\n}".encodeToByteArray(),
        "Feed.xml" to "<?xml version=\"1.0\"?>\n<feed>\n  <title>Garden news</title>\n  <entry>Spring planting</entry>\n</feed>".encodeToByteArray(),
        "Config.yaml" to "server:\n  port: 8080\n  host: localhost\nlogging:\n  level: info".encodeToByteArray(),
        "Main.kt" to "fun main() {\n\tprintln(\"hello\")\n}\n".encodeToByteArray(),
    )

    val html = s(
        """
        <html><head><title>Shop</title><style>body { color: red }</style></head>
        <body><h1>Welcome</h1><p>Opening hours</p><script>var cut = "the head ends inside this script
        """,
    )

    val rtf = s("""{\rtf1\ansi{\fonttbl{\f0 Arial;}}\f0 Hello world\par Second line}""")

    val all by lazy {
        mapOf(
            "Letter.docx" to docx,
            "Report.docm" to docx,
            "Budget.xlsx" to xlsx,
            "Deck.pptx" to pptx,
            "Notes.odt" to odt,
            "Steps.ods" to ods,
            "Trip.odp" to odp,
            "Pictured.docx" to withPicture,
            "Expenses.csv" to "Date,Item,Amount\n2026-03-01,Groceries,42.10\n2026-03-02,\"Bus, monthly\",55.00\n2026-03-04,Books,18.99\n".encodeToByteArray(),
            "Notes.txt" to txt,
            "Readme.md" to "# Plants\n\nWater the ferns on Sundays and keep the cactus dry.\n\n- Ferns\n- Cactus".encodeToByteArray(),
            "Page.html" to html,
            "Letter.rtf" to rtf,
            "Broken.docx" to byteArrayOf(0x50, 0x4B, 0x03, 0x04) + Random(5).nextBytes(2048),
        ) + text
    }
}
