package app.retriever.indexing

import android.app.Application
import android.app.NotificationManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import app.retriever.testRetriever
import org.junit.Assert.assertEquals
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
}
