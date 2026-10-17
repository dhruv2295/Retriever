package app.retriever

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.retriever.core.Kind
import app.retriever.core.SourceFile
import app.retriever.data.AccessService
import app.retriever.data.IndexService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
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
    private fun viewModel(retriever: app.retriever.core.Retriever) =
        StatusViewModel(retriever, IndexService(app, retriever), AccessService(app, demo = true))

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

    @Test fun failedActionLandsInStateInsteadOfCrashing() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val retriever = retrieverOver(driver = driver, files = listOf(photo))
        retriever.indexBatch(1.minutes)
        val vm = viewModel(retriever)
        // Collect so the counts have arrived before the store goes away.
        backgroundScope.launch { vm.uiState.collect {} }
        vm.uiState.first { it.counts.byState.isNotEmpty() }
        driver.close()

        vm.loadSkipped()
        assertEquals("That didn't work. Try again.", vm.uiState.first { it.error != null }.error)
    }
}
