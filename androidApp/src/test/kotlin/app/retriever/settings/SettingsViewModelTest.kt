package app.retriever.settings

import android.app.Application
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.retriever.core.Kind
import app.retriever.core.Retriever
import app.retriever.core.StopReason
import app.retriever.duplicates.retrieverOver
import app.retriever.indexing.IndexService
import app.retriever.model.ModelDownload
import app.retriever.model.ModelRepository
import app.retriever.search.RecentShortcuts
import app.retriever.testFile
import app.retriever.testRetriever
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SettingsViewModelTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val modelDir = File(app.filesDir, "models").apply { mkdirs() }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    @After fun tearDown() {
        appScope.cancel()
        Dispatchers.resetMain()
    }

    private fun viewModel(
        driver: JdbcSqliteDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY),
        retriever: Retriever = retrieverOver(driver),
    ): Pair<SettingsViewModel, Retriever> {
        val download = ModelDownload(app, modelDir)
        val vm = SettingsViewModel(
            retriever,
            ModelRepository(app, download, retriever, appScope),
            StorageService(app, retriever, download, modelDir),
            IndexService(app, retriever),
            appScope,
            RecentShortcuts(app),
        ) { error("no tuning in this test") }
        return vm to retriever
    }

    /** The view model saves on a real thread; this lets it and the test dispatcher both run until [done]. */
    private suspend fun TestScope.until(done: suspend () -> Boolean) {
        var tries = 0
        while (!done() && tries++ < 1000) {
            advanceUntilIdle()
            Thread.sleep(5)
        }
        assertTrue("never happened", done())
    }

    @Test fun aToggleIsSavedAndShown() = runTest {
        val (vm, retriever) = viewModel()
        assertTrue(vm.uiState.first().prefs.rememberSearches)

        vm.setRememberSearches(false)

        assertEquals(false, vm.uiState.first { !it.prefs.rememberSearches }.prefs.rememberSearches)
        assertEquals(false, retriever.prefs.value.rememberSearches)
        assertEquals(null, vm.uiState.value.message)
    }

    @Test fun anIndexingProgressTickDoesNotRebuildTheScreen() = runTest {
        val file = testFile("notes.txt", Kind.DOC)
        val retriever = testRetriever(files = listOf(file), ramGb = 3.0, extract = { emptyList() })
        val (vm, _) = viewModel(retriever = retriever)
        val seen = mutableListOf<SettingsUi>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.toList(seen) }
        advanceUntilIdle()
        val before = seen.size
        val stateBefore = retriever.state.value

        retriever.indexBatch(1.minutes)
        advanceUntilIdle()

        assertTrue("the batch must have moved the index state", retriever.state.value != stateBefore)
        assertEquals(before, seen.size)
    }

    @Test fun aFailedSaveBecomesAMessageNotACrash() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val (vm, retriever) = viewModel(driver)
        driver.close()

        vm.setMobileData(true)

        val shown = vm.uiState.first { it.message != null }
        assertTrue(shown.message!!.startsWith("Couldn’t save"))
        assertEquals(false, retriever.prefs.value.allowMobileData)
        vm.messageShown()
        assertEquals(null, vm.uiState.first { it.message == null }.message)
    }

    @Test fun freeingUpSpaceSaysSoOnceThenClears() = runTest {
        val (vm, _) = viewModel()

        vm.freeUpSpace()

        assertEquals("Search download removed", vm.uiState.first { it.message != null }.message)
        vm.messageShown()
        assertEquals(null, vm.uiState.first { it.message == null }.message)
    }

    @Test fun clearingRecentSearchesEmptiesTheListAndTheLauncherShortcuts() = runTest {
        val (vm, retriever) = viewModel()
        retriever.recordQuery("boarding pass")
        RecentShortcuts(app).push(retriever.recentQueries())
        assertEquals(1, ShortcutManagerCompat.getDynamicShortcuts(app).size)

        vm.clearRecentSearches()
        until { ShortcutManagerCompat.getDynamicShortcuts(app).isEmpty() }

        assertEquals(emptyList<String>(), retriever.recentQueries())
        assertEquals(0, ShortcutManagerCompat.getDynamicShortcuts(app).size)
        assertTrue("clearing keeps the switch as it was", retriever.prefs.value.rememberSearches)
    }

    @Test fun turningSaveRecentSearchesOffAlsoRemovesTheLauncherShortcuts() = runTest {
        val (vm, retriever) = viewModel()
        retriever.recordQuery("boarding pass")
        RecentShortcuts(app).push(retriever.recentQueries())

        vm.setRememberSearches(false)
        until { ShortcutManagerCompat.getDynamicShortcuts(app).isEmpty() }

        assertEquals(emptyList<String>(), retriever.recentQueries())
        assertEquals(0, ShortcutManagerCompat.getDynamicShortcuts(app).size)
    }

    @Test fun sampleModeStopsTheRunningBatchAndIndexesAgain() = runTest {
        val reading = CountDownLatch(1)
        val gate = CountDownLatch(1)
        // Text-only reads the document with no model, and holds the batch open mid-file.
        val retriever = testRetriever(files = listOf(testFile("notes.txt", Kind.DOC)), ramGb = 3.0, extract = {
            reading.countDown()
            gate.await()
            emptyList()
        })
        val (vm, _) = viewModel(retriever = retriever)
        val batch = appScope.async { retriever.indexBatch(1.minutes) }
        runInterruptible(Dispatchers.IO) { reading.await() }

        vm.setSampleMode(true)
        // The save runs on a real thread; restart() has run once its reader is queued.
        val work = WorkManager.getInstance(app)
        var tries = 0
        while (work.getWorkInfosForUniqueWork(IndexService.INDEX).get().isEmpty() && tries++ < 1000) {
            advanceUntilIdle()
            Thread.sleep(5)
        }
        gate.countDown()
        batch.await()

        assertEquals(StopReason.BUDGET, retriever.state.value.lastStop) // the batch was cancelled, not finished
        assertEquals(1, work.getWorkInfosForUniqueWork(IndexService.INDEX).get().size)
    }
}
