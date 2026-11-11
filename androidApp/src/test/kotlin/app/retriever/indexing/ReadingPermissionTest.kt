package app.retriever.indexing

import android.app.Application
import android.app.NotificationManager
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import app.retriever.core.Kind
import app.retriever.core.Words
import app.retriever.testFile
import app.retriever.testRetriever
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun decliningOnboardingContinuesOnceAndDefersTheHomeOfferForThisProcess() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        val retriever = testRetriever()
        var request: ((() -> Unit) -> Unit)? = null
        var continued = 0
        try {
            rule.setContent { request = rememberReadingPermission(retriever, onboarding = true) }
            rule.runOnIdle { request?.invoke { continued++ } }
            rule.waitUntil(5_000) { rule.onAllNodesWithText(Words.strings.notificationPermissionAsk).fetchSemanticsNodes().isNotEmpty() }
            rule.runOnIdle {
                assertEquals(0, continued)
                assertTrue(NotificationPermissionSession.onboardingAsked)
            }
            rule.onNodeWithText(Words.NOT_NOW).performClick()
            rule.waitUntil(5_000) { continued == 1 }
            assertTrue(retriever.prefs.value.notificationsDismissed)
            assertEquals(false, retriever.prefs.value.notificationsOfferDismissed)
            assertEquals(false, retriever.prefs.value.notificationsPermissionRequested)
            rule.runOnIdle { request?.invoke { continued++ } }
            rule.runOnIdle { assertEquals(2, continued) }
        } finally {
            NotificationPermissionSession.onboardingAsked = false
        }
    }

    @Test fun rotationDropsThePendingCustomPrompt() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        val retriever = testRetriever()
        val restore = StateRestorationTester(rule)
        var request: ((() -> Unit) -> Unit)? = null
        var continued = 0
        try {
            restore.setContent {
                request = rememberReadingPermission(retriever, onboarding = true)
            }
            rule.runOnIdle { requireNotNull(request).invoke { continued++ } }
            rule.waitUntil(5_000) { rule.onAllNodesWithText(Words.strings.notificationPermissionAsk).fetchSemanticsNodes().isNotEmpty() }
            restore.emulateSavedInstanceStateRestore()
            rule.runOnIdle { assertEquals(0, continued) }
            rule.onNodeWithText(Words.strings.notificationPermissionAsk).assertDoesNotExist()
            rule.runOnIdle { requireNotNull(request).invoke { continued++ } }
            rule.waitUntil(5_000) { rule.onAllNodesWithText(Words.strings.notificationPermissionAsk).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText(Words.NOT_NOW).performClick()
            rule.waitUntil(5_000) { continued == 1 }
        } finally {
            NotificationPermissionSession.onboardingAsked = false
        }
    }

    @Test fun setupStartsAfterSystemDenialWhileTheScreenRemains() = systemDenial(rotates = false)

    @Test fun rotationDropsThePendingActionBeforeSystemDenial() = systemDenial(rotates = true)

    private fun systemDenial(rotates: Boolean) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        val retriever = testRetriever()
        val restore = StateRestorationTester(rule)
        var launchedCode: Int? = null
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                launchedCode = requestCode
            }
        }
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        var request: ((() -> Unit) -> Unit)? = null
        var continued = 0
        try {
            restore.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    request = rememberReadingPermission(retriever, onboarding = true)
                }
            }
            rule.runOnIdle { requireNotNull(request).invoke { continued++ } }
            rule.waitUntil(5_000) { rule.onAllNodesWithText(Words.strings.notificationPermissionAsk).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText(Words.ALLOW).performClick()
            rule.waitUntil(5_000) { launchedCode != null }
            rule.runOnIdle { assertEquals(0, continued) }
            if (rotates) restore.emulateSavedInstanceStateRestore()
            rule.runOnIdle {
                assertEquals(0, continued)
                assertTrue(registry.dispatchResult(requireNotNull(launchedCode), mapOf(android.Manifest.permission.POST_NOTIFICATIONS to false)))
            }
            // A fake registry callback needs the same snapshot/recomposition flush as an Android result event.
            rule.waitForIdle()
            rule.waitUntil(5_000) { retriever.prefs.value.notificationsPermissionRequested }
            rule.runOnIdle { assertEquals(if (rotates) 0 else 1, continued) }
            assertNull("Declining setup notifications must keep Retriever in front", shadowOf(app).nextStartedActivity)
        } finally {
            NotificationPermissionSession.onboardingAsked = false
        }
    }

    /** Leaving the permission question never starts processing on the way out. */
    @Test fun aHeldTapIsDroppedWhenTheScreenGoesBeforeTheAnswer() {
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
        rule.runOnIdle { assertEquals(0, ran) }
    }

    private fun ask(retriever: app.retriever.core.Retriever) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        var request: ((() -> Unit) -> Unit)? = null
        rule.setContent { request = rememberReadingPermission(retriever) }
        rule.runOnIdle { request!! {} }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Show a notification while Retriever processes your files?").fetchSemanticsNodes().isNotEmpty() }
    }

    /** With no demo waiting: no promise about a demo that is already done. */
    @Test fun theDemoSentenceGoesOnceTheDemoIsDone() {
        ask(testRetriever())
        rule.onNodeWithText(PLAIN).assertExists()
    }

    @Test fun theDemoSentenceStaysWhileTheDemoRuns() {
        val retriever = testRetriever((1..30).map { testFile("doc$it.txt", Kind.DOC) })
        runBlocking { retriever.planTaste() }
        ask(retriever)
        rule.onNodeWithText("$PLAIN $SAMPLE_LINE").assertExists()
    }

    /** The ask after the first search's Continue ended setup part-way: no demo message will come, so none is promised. */
    @Test fun theDemoSentenceGoesOnceTheDemoIsStopped() {
        val retriever = testRetriever((1..30).map { testFile("doc$it.txt", Kind.DOC) })
        val sample = runBlocking {
            retriever.planTaste()
            retriever.finishSetup()
            retriever.tasteProgress().first()
        }
        assertTrue("files pending: $sample", sample.pending > 0)
        ask(retriever)
        rule.onNodeWithText(PLAIN).assertExists()
    }

    private companion object {
        const val PLAIN = "One small notice at the top of your screen while it works, so you can see how far it’s got or pause it. No sounds, no other alerts."
        const val SAMPLE_LINE = "We can also let you know when the demo is done."
    }
}
