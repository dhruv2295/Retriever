package app.retriever

import android.app.Application
import android.content.Intent
import android.os.Environment
import android.os.Looper
import android.os.StrictMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.startup.InitializationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.retriever.core.Retriever
import app.retriever.core.Words
import app.retriever.di.AppScope
import app.retriever.di.DemoMode
import app.retriever.indexing.IndexService
import app.retriever.indexing.Notifications
import app.retriever.indexing.ReadingService
import app.retriever.search.RecentShortcuts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowEnvironment

/** No graph overrides: a broken production definition must fail before the first screen can draw. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RetrieverApp::class, shadows = [NoFileAccess::class], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppLaunchTest {
    @get:Rule val rule = createEmptyComposeRule()

    @After fun tearDown() {
        GlobalContext.getOrNull()?.let { koin ->
            val job = koin.get<CoroutineScope>(AppScope).coroutineContext.job
            job.cancel()
            runBlocking {
                withTimeout(10_000) {
                    while (!job.isCompleted) {
                        shadowOf(Looper.getMainLooper()).idle()
                        delay(1)
                    }
                }
                job.join()
            }
        }
        stopKoin()
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX)
        StrictMode.setVmPolicy(StrictMode.VmPolicy.LAX)
    }

    @Test fun realApplicationLaunchesHello() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertTrue(app is RetrieverApp)
        // Robolectric runs no content providers: start the process lifecycle the app's onStart (uiVisible) follows, as on a phone.
        Robolectric.setupContentProvider(InitializationProvider::class.java)
        val koin = GlobalContext.get()
        assertNull(koin.get<DemoMode>().parts)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            rule.waitUntil(10_000) { koin.get<Retriever>().setUp.value != null }
            rule.onNodeWithText(Words.helloTitle(koin.get<Retriever>().textOnly)).assertIsDisplayed()
            // A first run starts at setup, which reports fully drawn at once: Home's report would come only once setup ends.
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it }
            rule.waitUntil(5_000) {
                // Robolectric draws no frames on a surface, so the draw that delivers the report is dispatched by hand.
                activity.window.decorView.viewTreeObserver.dispatchOnDraw()
                activity.fullyDrawnReporter.isFullyDrawnReported
            }
            scenario.onActivity { assertEquals(Lifecycle.State.RESUMED, it.lifecycle.currentState) }
            app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            rule.waitForIdle()
        }
        // Q1 11: a recreation (rotation, restore) of a notification-opened activity must not open Status again. In this test,
        // not its own: Koin's Compose context outlives stopKoin between tests in one class.
        val status = Intent(app, MainActivity::class.java).setAction(Notifications.OPEN_STATUS)
        ActivityScenario.launch<MainActivity>(status).use { scenario ->
            scenario.onActivity { assertEquals(1, it.statusRequest) }
            scenario.recreate()
            scenario.onActivity { assertEquals(0, it.statusRequest) }
        }
        // A shortcut's words, trimmed, reach the nav host as a request, and a recreation does not run them again.
        val search = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(RecentShortcuts.EXTRA, " bill ")
        ActivityScenario.launch<MainActivity>(search).use { scenario ->
            scenario.onActivity { assertEquals(1 to "bill", it.searchRequest) }
            scenario.recreate()
            scenario.onActivity { assertNull(it.searchRequest) }
        }
        // Blank words are no request.
        for (blank in listOf("", "   ")) {
            val empty = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(RecentShortcuts.EXTRA, blank)
            ActivityScenario.launch<MainActivity>(empty).use { scenario -> scenario.onActivity { assertNull(it.searchRequest) } }
        }
        // A notification Resume starts once while the activity is visible. Recreating its retained intent must not
        // undo a subsequent user Pause, even though it still carries the Resume action.
        val resume = Intent(app, MainActivity::class.java).setAction(Notifications.RESUME_PROCESSING)
        ActivityScenario.launch<MainActivity>(resume).use { scenario ->
            scenario.onActivity { assertEquals(1, it.statusRequest) }
            rule.waitUntil(10_000) { shadowOf(app).peekNextStartedService()?.component?.className == ReadingService::class.java.name }
            val paused = koin.get<CoroutineScope>(AppScope).launch { koin.get<IndexService>().pause() }
            rule.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                paused.isCompleted
            }
            scenario.recreate()
            rule.waitForIdle()
            scenario.onActivity { assertEquals(0, it.statusRequest) }
            assertTrue(koin.get<Retriever>().prefs.value.paused)
        }
        // Recents can create a new activity with no saved Bundle while retaining the old notification action.
        val history = Intent(resume).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        ActivityScenario.launch<MainActivity>(history).use { scenario ->
            scenario.onActivity {
                assertTrue(it.intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0)
                assertEquals(0, it.statusRequest)
            }
            rule.waitForIdle()
            assertTrue(koin.get<Retriever>().prefs.value.paused)
        }
    }
}

/** Robolectric has no primary storage volume; model the first launch before all-files access is granted. */
@Implements(Environment::class)
class NoFileAccess : ShadowEnvironment() {
    companion object {
        @Implementation
        @JvmStatic
        fun isExternalStorageManager() = false
    }
}
