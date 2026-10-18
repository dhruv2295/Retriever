package app.retriever.viewer

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntSize
import app.retriever.ShotTheme
import app.retriever.core.Kind
import app.retriever.core.SourceFile
import app.retriever.nav.PdfRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PdfPageScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun matchedHereAppearsOnTheMatchedPageOnly() {
        rule.setContent {
            ShotTheme(false) {
                Column {
                    PdfPageFrame(null, IntSize(100, 140), "Page 1 of 3", matched = false)
                    PdfPageFrame(null, IntSize(100, 140), "Page 2 of 3", matched = true)
                    PdfPageFrame(null, IntSize(100, 140), "Page 3 of 3", matched = false)
                }
            }
        }
        assertEquals(1, rule.onAllNodesWithText("Matched here").fetchSemanticsNodes().size)
    }

    @Test fun aPdfOpenedWithoutASearchMatchMarksNoPage() {
        val pdf = SourceFile("content://test/a.pdf", "a.pdf", "", "application/pdf", Kind.PDF, 100, 1)
        assertNull((Opener.routeFor(pdf, "") as PdfRoute).matchedIndex())
        assertEquals(11, (Opener.routeFor(pdf, "p. 12") as PdfRoute).matchedIndex())
    }

    @Test fun failedStateOffersOpenInAnotherApp() {
        var opened = false
        rule.setContent { ShotTheme(false) { PdfFailed(onOpen = { opened = true }) } }
        rule.onNodeWithText("Couldn't open this PDF").assertExists()
        rule.onNodeWithText("Open in another app").performClick()
        assertTrue(opened)
    }

    @Test fun zoomBucketGrowsWithZoomAndCaps() {
        assertEquals(1, zoomBucket(1.0f))
        assertEquals(2, zoomBucket(1.7f))
        assertEquals(3, zoomBucket(2.6f))
        assertEquals(3, zoomBucket(9f))
    }

    @Test fun renderScaleNeverPassesTheBitmapCap() {
        assertEquals(2f, renderScale(500, 700, 1000), 0f)
        assertEquals(4096f / 700, renderScale(500, 700, 20000), 0.001f)
        assertEquals(4096f / 5000, renderScale(5000, 700, 20000), 0.001f)
    }
}
