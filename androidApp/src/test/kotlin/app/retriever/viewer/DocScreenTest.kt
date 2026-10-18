package app.retriever.viewer

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import app.retriever.ShotTheme
import app.retriever.core.ChunkText
import app.retriever.core.FileState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DocScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun show(ui: DocUi) = rule.setContent { ShotTheme(false) { DocContent(ui, PaddingValues(), openWith = {}) } }

    private val chunks = (0..40).map { ChunkText(it, "p. ${it / 10 + 1}", "Paragraph number $it of the lease") } +
        ChunkText(41, "p. 5", "The deposit is refundable")
    private val read = FileState.INDEXED to chunks

    @Test fun loadsTheParagraphsAndOpensOnTheOneWithTheQuery() = runTest {
        val ui = loadDoc("deposit", "") { read } as DocUi.Text
        assertEquals(42, ui.paragraphs.size)
        assertEquals(41, ui.matchIndex)
    }

    @Test fun theHitsLocatorNarrowsTheSearch() = runTest {
        assertEquals(20, (loadDoc("paragraph", "p. 3") { read } as DocUi.Text).matchIndex)
        assertEquals(0, (loadDoc("zzz", "") { read } as DocUi.Text).matchIndex)
    }

    @Test fun textScrollsToTheMatchedParagraphAndHighlightsIt() = runTest {
        show(loadDoc("deposit", "") { read })
        rule.onNodeWithText("The deposit is refundable").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTextCount("Paragraph number 0 of the lease") == 0)
    }

    @Test fun aFileWithNoChunksIsEmpty() = runTest {
        assertEquals(DocUi.Empty, loadDoc("a", "") { FileState.INDEXED to emptyList() })
        show(DocUi.Empty)
        rule.onNodeWithText("Retriever couldn't read text in this file").assertIsDisplayed()
        rule.onNodeWithText("Open in another app").assertIsDisplayed()
    }

    @Test fun aFileStillWaitingToBeReadSaysSoNotThatItCouldntBeRead() = runTest {
        assertEquals(DocUi.NotReadYet, loadDoc("a", "") { FileState.PENDING to emptyList() })
        show(DocUi.NotReadYet)
        rule.onNodeWithText("Retriever hasn't read this yet").assertIsDisplayed()
        rule.onNodeWithText("Open in another app").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTextCount("Retriever couldn't read text in this file"))
    }

    @Test fun aSkippedFileWithNoTextIsEmptyAndAMissingOneFailed() = runTest {
        assertEquals(DocUi.Empty, loadDoc("a", "") { FileState.SKIPPED to emptyList() })
        assertEquals(DocUi.Failed, loadDoc("a", "") { null })
    }

    @Test fun aFailingReadShowsTheTroubleState() = runTest {
        assertEquals(DocUi.Failed, loadDoc("a", "") { error("gone") })
        show(DocUi.Failed)
        rule.onNodeWithText("Couldn't open this file").assertIsDisplayed()
        rule.onNodeWithText("Open in another app").assertIsDisplayed()
    }

    @Test fun loadingShowsNoText() {
        show(DocUi.Loading)
        assertEquals(0, rule.onAllNodesWithTextCount("Couldn't open this file"))
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodesWithText(text).fetchSemanticsNodes().size
}
