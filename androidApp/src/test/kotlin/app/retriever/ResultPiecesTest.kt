package app.retriever

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.retriever.core.Kind
import app.retriever.core.SourceFile
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

    @Test fun locatorParsing() {
        assertEquals(12, Opener.locatorToPage("p. 12"))
        assertNull(Opener.locatorToPage("p. 0"))
        assertNull(Opener.locatorToPage("p. x"))
        assertNull(Opener.locatorToPage("12"))
        assertNull(Opener.locatorToPage("3:41"))
        assertEquals(37_000L, Opener.locatorToMs("0:37"))
        assertEquals(3_723_000L, Opener.locatorToMs("1:02:03"))
        assertNull(Opener.locatorToMs("p. 12"))
    }

    @Test fun routeForOpensTheMatch() {
        assertEquals(PdfRoute(pdf.uri, 12, pdf.name), Opener.routeFor(pdf, "p. 12"))
        assertEquals(PlayerRoute(video.uri, video.mime, 37_000, video.name), Opener.routeFor(video, "0:37"))
        assertNull(Opener.routeFor(pdf, ""))
        assertNull(Opener.routeFor(doc, "p. 3"))
        assertNull(Opener.routeFor(video, "p. 3"))
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
