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
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import app.retriever.testRetriever
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

    @Test fun aDenialStillStartsTheWorkAndTheNextTapDoesNotAskAgain() = systemDenial(rotates = false)

    @Test fun rotationDropsThePendingActionBeforeSystemDenial() = systemDenial(rotates = true)

    private fun systemDenial(rotates: Boolean) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        val retriever = testRetriever()
        val restore = StateRestorationTester(rule)
        var launches = 0
        var launchedCode: Int? = null
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                launches++
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
                    request = rememberReadingPermission(retriever)
                }
            }
            rule.runOnIdle { requireNotNull(request).invoke { continued++ } }
            rule.runOnIdle {
                assertEquals("Android's prompt is the only question", 1, launches)
                assertEquals(0, continued)
                assertTrue(NotificationPermissionSession.asked)
            }
            if (rotates) restore.emulateSavedInstanceStateRestore()
            rule.runOnIdle {
                assertEquals(0, continued)
                assertTrue(registry.dispatchResult(requireNotNull(launchedCode), mapOf(android.Manifest.permission.POST_NOTIFICATIONS to false)))
            }
            // A fake registry callback needs the same snapshot/recomposition flush as an Android result event.
            rule.waitForIdle()
            rule.waitUntil(5_000) { retriever.prefs.value.notificationsPermissionRequested }
            rule.runOnIdle { assertEquals(if (rotates) 0 else 1, continued) }
            assertNull("A denial keeps Retriever in front", shadowOf(app).nextStartedActivity)
            rule.runOnIdle { requireNotNull(request).invoke { continued++ } }
            rule.runOnIdle {
                assertEquals(1, launches)
                assertEquals(if (rotates) 1 else 2, continued)
            }
        } finally {
            NotificationPermissionSession.asked = false
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
        try {
            rule.setContent { if (shown) request = rememberReadingPermission(retriever) }
            rule.runOnIdle { requireNotNull(request).invoke { ran++ } }
            rule.runOnIdle { assertEquals("held while the question shows", 0, ran) }
            shown = false
            rule.runOnIdle { assertEquals(0, ran) }
        } finally {
            NotificationPermissionSession.asked = false
        }
    }
}
