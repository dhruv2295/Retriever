package app.retriever.indexing

import android.app.Application
import android.app.NotificationManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.retriever.core.Kind
import app.retriever.core.Part
import app.retriever.core.Piece
import app.retriever.core.TRY_AFTER_FILES
import app.retriever.testFile
import app.retriever.testRetriever
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReadingPermissionTest {
    @get:Rule val rule = createComposeRule()

    /** Q1 5: a tap held for the notification question still runs when the screen goes first (a rotation recreates it). */
    @Test fun aHeldTapRunsWhenTheScreenGoesBeforeTheAnswer() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        val retriever = testRetriever()
        var shown by mutableStateOf(true)
        var request: ((() -> Unit) -> Unit)? = null
        var ran = 0
        rule.setContent { if (shown) request = rememberReadingPermission(retriever) }
        rule.runOnIdle { request!! { ran++ } }
        rule.runOnIdle { assertEquals("held while the question shows", 0, ran) }
        shown = false
        rule.runOnIdle { assertEquals(1, ran) }
    }

    private fun ask(retriever: app.retriever.core.Retriever) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        var request: ((() -> Unit) -> Unit)? = null
        rule.setContent { request = rememberReadingPermission(retriever) }
        rule.runOnIdle { request!! {} }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Show a notification while Retriever processes your files?").fetchSemanticsNodes().isNotEmpty() }
    }

    /** The first Home's ask, once the sample is read: no promise about a sample that is already done. */
    @Test fun theSampleSentenceGoesOnceTheSampleIsDone() {
        ask(testRetriever())
        rule.onNodeWithText(PLAIN).assertExists()
    }

    @Test fun theSampleSentenceStaysWhileTheSampleReads() {
        val retriever = testRetriever((1..30).map { testFile("doc$it.txt", Kind.DOC) })
        runBlocking { retriever.planTaste() }
        ask(retriever)
        rule.onNodeWithText("$PLAIN $SAMPLE_LINE").assertExists()
    }

    /** Ready to try with files still to read (the ask after Try): the one message is claimed, so nothing is promised. */
    @Test fun theSampleSentenceGoesOnceTheSampleIsReadyToTry() {
        var extracted = 0
        lateinit var retriever: app.retriever.core.Retriever
        retriever = testRetriever((1..30).map { testFile("doc$it.txt", Kind.DOC) }, extract = {
            if (++extracted > TRY_AFTER_FILES) retriever.cancelBatch()
            listOf(Piece("", listOf(Part.Text("read content")), "read content", "read content"))
        }).apply { uiVisible = true }
        val sample = runBlocking {
            retriever.planTaste()
            retriever.taste()
            retriever.tasteProgress().first()
        }
        assertTrue("ready with files pending: $sample", sample.readyToTry && sample.pending > 0)
        ask(retriever)
        rule.onNodeWithText(PLAIN).assertExists()
    }

    private companion object {
        const val PLAIN = "One small notice at the top of your screen while it works, so you can see how far it’s got or pause it. No sounds, no other alerts."
        const val SAMPLE_LINE = "We can also let you know when your sample is ready."
    }
}
