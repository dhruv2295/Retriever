package app.retriever

import android.app.Application
import android.content.Intent
import android.os.Environment
import android.os.StrictMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.retriever.core.Retriever
import app.retriever.core.Words
import app.retriever.di.AppScope
import app.retriever.di.DemoMode
import app.retriever.indexing.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
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
            runBlocking { koin.get<CoroutineScope>(AppScope).coroutineContext.job.cancelAndJoin() }
        }
        stopKoin()
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX)
        StrictMode.setVmPolicy(StrictMode.VmPolicy.LAX)
    }

    @Test fun realApplicationLaunchesHello() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertTrue(app is RetrieverApp)
        val koin = GlobalContext.get()
        assertNull(koin.get<DemoMode>().parts)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            rule.waitUntil(10_000) { koin.get<Retriever>().setUp.value != null }
            rule.onNodeWithText(Words.helloTitle(koin.get<Retriever>().textOnly)).assertIsDisplayed()
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
