package app.retriever.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.retriever.ShotTheme
import app.retriever.core.Kind
import app.retriever.core.ParsedQuery
import app.retriever.core.ReadExtent
import app.retriever.core.SearchEngine
import app.retriever.core.SourceFile
import app.retriever.home.HomeUi
import app.retriever.nav.DocRoute
import app.retriever.nav.PdfRoute
import app.retriever.nav.PhotoRoute
import app.retriever.nav.PlayerRoute
import app.retriever.shot
import app.retriever.tallWindowForBigText
import app.retriever.ui.Format
import app.retriever.ui.components.FileRow
import app.retriever.ui.components.ThumbCell
import app.retriever.ui.components.highlighted
import app.retriever.ui.components.nameHighlighted
import app.retriever.viewer.Opener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResultPiecesTest {
    @get:Rule val rule = createComposeRule()

    private val style = SpanStyle(fontWeight = FontWeight.Bold)

    private fun file(name: String, kind: Kind, mime: String = "application/octet-stream") =
        SourceFile("demo:$name", name, "Documents", mime, kind, 1_200_000, 1_760_000_000_000)

    private val pdf = file("Quarterly report.pdf", Kind.PDF, "application/pdf")
    private val video = file("Birthday.mp4", Kind.VIDEO, "video/mp4")
    private val doc = file("Notes.docx", Kind.DOC)

    // Pure pieces

    @Test fun highlightedBalanced() {
        val out = highlighted("a \u0002b\u0003 c \u0002d\u0003", style)
        assertEquals("a b c d", out.text)
        assertEquals(listOf(2 to 3, 6 to 7), out.spanStyles.map { it.start to it.end })
    }

    @Test fun highlightedUnbalanced() {
        val unclosed = highlighted("a \u0002bc", style)
        assertEquals("a bc", unclosed.text)
        assertEquals(listOf(2 to 4), unclosed.spanStyles.map { it.start to it.end })

        val stray = highlighted("a\u0003b", style)
        assertEquals("ab", stray.text)
        assertEquals(0, stray.spanStyles.size)

        val nested = highlighted("\u0002a\u0002b\u0003c", style)
        assertEquals("abc", nested.text)
        assertEquals(listOf(0 to 2), nested.spanStyles.map { it.start to it.end })
    }

    @Test fun highlightedNone() {
        val out = highlighted("plain text", style)
        assertEquals("plain text", out.text)
        assertEquals(0, out.spanStyles.size)
    }

    @Test fun nameHighlightedBoldsEachWordIgnoringCase() {
        val out = nameHighlighted("Quarterly Report.pdf", "REPORT quarterly", style)
        assertEquals("Quarterly Report.pdf", out.text)
        assertEquals(listOf(0 to 9, 10 to 16), out.spanStyles.map { it.start to it.end })
        assertEquals(0, nameHighlighted("Quarterly Report.pdf", "  ", style).spanStyles.size)
    }

    @Test fun nameHighlightedMatchesWholeWordsAsKeywordSearchDoes() {
        // "on" inside "json" is no match; a file found by its name is bold wherever the words are in it, little words too.
        assertEquals(0, nameHighlighted("queries.json", "dinner on friday", style).spanStyles.size)
        assertEquals(listOf(6 to 8), nameHighlighted("Notes on tax.txt", "on", style).spanStyles.map { it.start to it.end })
        // The last word is a prefix, as in the search itself; earlier ones must match whole.
        assertEquals(listOf(0 to 7), nameHighlighted("Invoice_2024.pdf", "invo", style).spanStyles.map { it.start to it.end })
        assertEquals(0, nameHighlighted("Invoice_2024.pdf", "invo bill", style).spanStyles.size)
    }

    @Test fun relaxedNoteNamesWhatWasDroppedAndWhatWasKept() {
        val parsed = ParsedQuery("", emptySet(), 1, 2, listOf("PDFs", "last week"), dateLabel = "last week")
        assertEquals("No PDFs from last week, so here is everything.", parsed.relaxedNote(Format.scope(null, false)))
        assertEquals("No PDFs from last week, so here are all your starred files.", parsed.relaxedNote(Format.scope(null, true)))
        assertEquals("No PDFs from last week, so here are all your starred photos.", parsed.relaxedNote(Format.scope(Kind.PHOTO, true)))
        assertEquals("No PDFs from last week, so here are all your PDFs.", parsed.relaxedNote(Format.scope(Kind.PDF, false)))
    }

    @Test fun locatorLabels() {
        assertEquals("Page 12", Format.locatorLabel("p. 12"))
        assertEquals("3:41", Format.locatorLabel("3:41"))
        assertEquals("1:02:03", Format.locatorLabel("1:02:03"))
        assertNull(Format.locatorLabel(""))
        assertNull(Format.locatorLabel("p. 0"))
        assertNull(Format.locatorLabel("chapter 2"))
    }

    @Test fun locatorSpoken() {
        assertEquals("page 12", Format.locatorSpoken("p. 12"))
        assertEquals("3 minutes 41 seconds", Format.locatorSpoken("3:41"))
        assertEquals("1 hour 2 minutes 3 seconds", Format.locatorSpoken("1:02:03"))
        assertEquals("1 minute 1 second", Format.locatorSpoken("1:01"))
        assertEquals("2 minutes", Format.locatorSpoken("2:00"))
        assertNull(Format.locatorSpoken(""))
    }

    @Test fun readNotes() {
        assertEquals("Partly read · only the beginning is included", Format.readNote(ReadExtent.FIRST_PART))
        assertEquals("Partly read · more meaning search to come", Format.readNote(ReadExtent.DEEPENING))
        assertNull(Format.readNote(ReadExtent.WHOLE))
        assertNull(Format.readNote(null))
    }

    @Test fun etaBoundaries() {
        assertEquals("less than a minute left", Format.eta(0))
        assertEquals("about a minute left", Format.eta(1))
        assertEquals("about 2 minutes left", Format.eta(2))
        assertEquals("about 59 minutes left", Format.eta(59))
        assertEquals("about an hour left", Format.eta(60))
        assertEquals("about an hour left", Format.eta(89))
        assertEquals("about 2 hours left", Format.eta(90))
        assertEquals("about 47 hours left", Format.eta(47 * 60))
        assertEquals("about 2 days left", Format.eta(48 * 60))
    }

    @Test fun routeForOpensTheMatch() {
        assertEquals(PdfRoute(pdf.uri, 12, pdf.name, matched = true), Opener.routeFor(pdf, "p. 12"))
        // Opened from a list or found by name: page 1, and nothing says a search matched there.
        assertEquals(PdfRoute(pdf.uri, 1, pdf.name, matched = false), Opener.routeFor(pdf, ""))
        assertEquals(PlayerRoute(video.uri, video.mime, 37_000, video.name), Opener.routeFor(video, "0:37"))
        assertEquals(PlayerRoute(video.uri, video.mime, 0, video.name), Opener.routeFor(video, ""))
        val audio = file("Memo.m4a", Kind.AUDIO, "audio/mp4")
        assertEquals(PlayerRoute(audio.uri, audio.mime, 0, audio.name), Opener.routeFor(audio, "p. 3"))
        assertEquals(DocRoute(7, doc.uri, doc.name, doc.mime, "p. 3", "tax"), Opener.routeFor(doc, "p. 3", 7, "tax"))
        assertEquals(DocRoute(0, doc.uri, doc.name, doc.mime), Opener.routeFor(doc))
        val photo = file("a.jpg", Kind.PHOTO, "image/jpeg")
        assertEquals(PhotoRoute(photo.uri, photo.name, photo.mime), Opener.routeFor(photo))
        val shot = file("s.png", Kind.SCREENSHOT, "image/png")
        assertEquals(PhotoRoute(shot.uri, shot.name, shot.mime), Opener.routeFor(shot))
        assertNull(Opener.routeFor(file("x.zip", Kind.OTHER, "application/zip")))
    }

    // Rendered pieces

    @Test fun thumbCellSemantics() {
        var opened = false
        var similar = false
        rule.setContent {
            ShotTheme(dark = false) {
                ThumbCell(
                    video,
                    onClick = { opened = true },
                    modifier = Modifier.width(120.dp),
                    locator = "3:41",
                    onLongClick = { similar = true },
                    onLongClickLabel = "Find similar",
                )
            }
        }
        val node = rule.onNodeWithContentDescription("Video, Birthday.mp4, ${Format.date(video.modifiedAt)}, at 3 minutes 41 seconds")
        node.assertExists()
        val config = node.fetchSemanticsNode().config
        assertEquals("Play from 3:41", config[SemanticsActions.OnClick].label)
        assertEquals("Find similar", config[SemanticsActions.OnLongClick].label)
        config[SemanticsActions.OnClick].action?.invoke()
        config[SemanticsActions.OnLongClick].action?.invoke()
        assertEquals(true, opened)
        assertEquals(true, similar)
    }

    @Test fun thumbCellPlainFileSaysOpen() {
        rule.setContent { ShotTheme(dark = false) { ThumbCell(doc, onClick = {}, modifier = Modifier.width(120.dp)) } }
        val node = rule.onNodeWithContentDescription("Document, Notes.docx, ${Format.date(doc.modifiedAt)}")
        assertEquals("Open", node.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
    }

    @Test fun thumbCellSaysWhenOnlyPartWasRead() {
        rule.setContent {
            ShotTheme(dark = false) { ThumbCell(pdf, onClick = {}, modifier = Modifier.width(120.dp), readNote = Format.readNote(ReadExtent.FIRST_PART)) }
        }
        rule.onNodeWithContentDescription(
            "PDF, Quarterly report.pdf, ${Format.date(pdf.modifiedAt)}, Partly read · only the beginning is included",
        ).assertExists()
    }

    @Test fun fileRowShowsItsReadNote() {
        rule.setContent { ShotTheme(dark = false) { FileRow(doc, readNote = Format.readNote(ReadExtent.DEEPENING), onClick = {}) } }
        rule.onNodeWithText("Partly read · more meaning search to come").assertExists()
    }

    @Test fun onlyAFileFoundByItsNameIsBold() {
        fun hit(id: Long, name: String, byName: Boolean) = SearchEngine.Hit(file(name, Kind.DOC), id, 0, "", null, 1.0, byName)
        val ui = SearchUi().answered("invoice", listOf(hit(1, "Invoice March.docx", true), hit(2, "Invoice April.docx", false)))
        rule.setContent { ShotTheme(dark = false) { SearchPane(ui, HomeUi(), SearchActions()) } }
        fun bold(name: String) = rule.onNodeWithText(name).fetchSemanticsNode().config[SemanticsProperties.Text].first().spanStyles
        assertEquals(listOf(0 to 7), bold("Invoice March.docx").map { it.start to it.end })
        assertEquals(0, bold("Invoice April.docx").size)
    }

    @Test fun fileRowLight() = fileRows("fileRow_light", dark = false)

    @Test fun fileRowDark() = fileRows("fileRow_dark", dark = true)

    @Test fun fileRowBigText() = fileRows("fileRow_text1_5", dark = false, fontScale = 1.5f)

    @Test fun thumbCellLight() = thumbCells("thumbCell_light", dark = false)

    @Test fun thumbCellDark() = thumbCells("thumbCell_dark", dark = true)

    @Test fun thumbCellBigText() = thumbCells("thumbCell_text1_5", dark = false, fontScale = 1.5f)

    private fun fileRows(name: String, dark: Boolean, fontScale: Float = 1f) {
        tallWindowForBigText(fontScale)
        rule.setContent {
            ShotTheme(dark, fontScale) {
                Column {
                    FileRow(
                        pdf,
                        snippet = "…the \u0002quarterly\u0003 numbers show steady \u0002growth\u0003 across every region we sell in, up from last year…",
                        locator = "p. 12",
                        query = "quarterly growth",
                        onClick = {},
                    )
                    FileRow(doc, snippet = "Notes about the money we set aside each month for the trip", query = "savings", onClick = {})
                    FileRow(file("Savings plan.xlsx", Kind.OTHER), query = "savings", onClick = {})
                    FileRow(video, snippet = "…and then everyone sang happy birthday…", locator = "3:41", onClick = {})
                    FileRow(video, locator = "1:02:03", onClick = {})
                }
            }
        }
        rule.shot(name)
    }

    private fun thumbCells(name: String, dark: Boolean, fontScale: Float = 1f) {
        tallWindowForBigText(fontScale)
        rule.setContent {
            ShotTheme(dark, fontScale) {
                Row(Modifier.padding(8.dp)) {
                    ThumbCell(pdf, onClick = {}, modifier = Modifier.width(110.dp).padding(4.dp), locator = "p. 12")
                    ThumbCell(video, onClick = {}, modifier = Modifier.width(110.dp).padding(4.dp), locator = "3:41")
                    ThumbCell(doc, onClick = {}, modifier = Modifier.width(110.dp).padding(4.dp))
                }
            }
        }
        rule.shot(name)
    }
}
