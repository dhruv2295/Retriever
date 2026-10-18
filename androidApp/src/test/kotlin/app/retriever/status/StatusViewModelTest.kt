package app.retriever.status

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.retriever.ShotTheme
import app.retriever.access.AccessService
import app.retriever.core.IndexState
import app.retriever.core.Kind
import app.retriever.core.Prefs
import app.retriever.core.SourceFile
import app.retriever.duplicates.retrieverOver
import app.retriever.home.HomeViewModel
import app.retriever.indexing.IndexService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StatusViewModelTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val photo = SourceFile("content://test/a.jpg", "a.jpg", "/Pictures", "image/jpeg", Kind.PHOTO, 100, 1)

    // Demo mode grants Access.ALL, which stands in for a phone with all-files access; Robolectric cannot answer Environment.isExternalStorageManager().
    private fun viewModel(retriever: app.retriever.core.Retriever) = StatusViewModel(retriever, IndexService(app, retriever), AccessService(app, demo = true))

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun pauseAndResumeShowInTheOneUiState() = runTest {
        val retriever = retrieverOver(files = listOf(photo))
        retriever.indexBatch(1.minutes)
        val vm = viewModel(retriever)
        assertFalse(vm.uiState.first().prefs.paused)

        vm.pause()
        assertTrue(vm.uiState.first { it.prefs.paused }.prefs.paused)
        vm.resume()
        assertFalse(vm.uiState.first { !it.prefs.paused }.prefs.paused)
        assertEquals(null, vm.uiState.value.error)
    }

    @Test fun theHeaderSharesHomesOnceOnlyCelebration() = runTest {
        val retriever = retrieverOver(files = listOf(photo))
        retriever.indexBatch(1.minutes)
        val vm = viewModel(retriever)
        assertTrue(vm.celebration.due())
        vm.celebration.done()
        advanceUntilIdle()
        assertFalse(vm.celebration.due())
        // Home's status asks the same store flag, and so does a relaunched Status.
        assertFalse(HomeViewModel(retriever, AccessService(app, demo = true), IndexService(app, retriever)).celebration.due())
        assertFalse(viewModel(retriever).celebration.due())
    }

    @Test fun firstEmissionHasNoCountsYet() = runTest {
        val vm = viewModel(retrieverOver(files = listOf(photo)))
        assertNull(vm.uiState.value.counts)
        assertNotNull(vm.uiState.first { it.counts != null }.counts)
    }

    @Test fun failedActionLandsInStateInsteadOfCrashing() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val retriever = retrieverOver(driver = driver, files = listOf(photo))
        retriever.indexBatch(1.minutes)
        val vm = viewModel(retriever)
        // Collect so the counts have arrived before the store goes away.
        backgroundScope.launch { vm.uiState.collect {} }
        vm.uiState.first { it.counts?.byState?.isNotEmpty() == true }
        driver.close()

        vm.loadSkipped()
        assertEquals("That didn't work. Try again.", vm.uiState.first { it.error != null }.error)
    }
}

/** Before the first count arrives the screen shows its title and a progress bar, never "0 of 0" or "All caught up". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FirstFrameStatusViewModelTest {
    @get:Rule val rule = createComposeRule()

    @Test fun noCountsDrawsNeitherZeroOfZeroNorCaughtUp() {
        rule.setContent {
            ShotTheme(dark = false) { StatusContent(null, Prefs(), IndexState(), emptyList(), skippedOpen = false, batteryHint = false) }
        }
        rule.onNodeWithText("Checking your files").assertExists()
        rule.onNodeWithText("All caught up").assertDoesNotExist()
        rule.onAllNodesWithText("0 of 0", substring = true).assertCountEquals(0)
    }
}
