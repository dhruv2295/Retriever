package app.retriever

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
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
}
