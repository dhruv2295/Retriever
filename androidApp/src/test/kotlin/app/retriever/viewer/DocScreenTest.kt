package app.retriever.viewer

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.retriever.ShotTheme
import app.retriever.core.ChunkText
import app.retriever.core.FileState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
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

    @Test fun theMatchesAreFoundOffTheMainThread() = runTest {
        val main = Thread.currentThread()
        var readOn: Thread? = null
        val ui = docUi("deposit", "") {
            readOn = Thread.currentThread()
            read
        }.first()
        assertEquals(41, (ui as DocUi.Text).matchIndex)
        assertNotSame(main, readOn)
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
        rule.onNodeWithText("Retriever couldn't find text in this file").assertIsDisplayed()
        rule.onNodeWithText("Open in another app").assertIsDisplayed()
    }

    @Test fun aFileStillWaitingToBeReadSaysSoNotThatItCouldntBeRead() = runTest {
        assertEquals(DocUi.NotReadYet, loadDoc("a", "") { FileState.PENDING to emptyList() })
        show(DocUi.NotReadYet)
        rule.onNodeWithText("Retriever hasn't processed this yet").assertIsDisplayed()
        rule.onNodeWithText("Open in another app").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTextCount("Retriever couldn't find text in this file"))
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

    @Test fun everyParagraphWithTheWordsIsAMatchAndStepsWrapRound() = runTest {
        val ui = loadDoc("paragraph", "") { read } as DocUi.Text
        assertEquals((0..40).toList(), ui.matches)
        assertEquals(1, stepMatch(ui.matches, 0, 1))
        assertEquals(0, stepMatch(ui.matches, 40, 1))
        assertEquals(40, stepMatch(ui.matches, 0, -1))
        assertEquals(emptyList<Int>(), (loadDoc("zzz", "") { read } as DocUi.Text).matches)
        assertEquals(null, stepMatch(emptyList(), 0, 1))
    }

    @Test fun theMatchBarStepsToTheNextMatch() = runTest {
        show(loadDoc("lease", "") { read })
        rule.onNodeWithText("Match 1 of 41").assertIsDisplayed()
        rule.onNodeWithContentDescription("Next match").performClick()
        rule.onNodeWithText("Match 2 of 41").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodesWithText(text).fetchSemanticsNodes().size
}
