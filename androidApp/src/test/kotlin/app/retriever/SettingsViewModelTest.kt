package app.retriever

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.retriever.data.IndexService
import app.retriever.data.ModelRepository
import app.retriever.data.StorageService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
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

    private fun viewModel(driver: JdbcSqliteDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)): Pair<SettingsViewModel, app.retriever.core.Retriever> {
        val retriever = retrieverOver(driver)
        val download = ModelDownload(app, modelDir)
        val vm = SettingsViewModel(
            retriever,
            ModelRepository(app, download, retriever, appScope),
            StorageService(app, retriever, download, modelDir),
            IndexService(app, retriever),
            appScope,
        ) { error("no tuning in this test") }
        return vm to retriever
    }

    @Test fun aToggleIsSavedAndShown() = runTest {
        val (vm, retriever) = viewModel()
        assertTrue(vm.uiState.first().prefs.rememberSearches)

        vm.setRememberSearches(false)

        assertEquals(false, vm.uiState.first { !it.prefs.rememberSearches }.prefs.rememberSearches)
        assertEquals(false, retriever.prefs.value.rememberSearches)
        assertEquals(null, vm.uiState.value.message)
    }

    @Test fun aFailedSaveBecomesAMessageNotACrash() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val (vm, retriever) = viewModel(driver)
        driver.close()

        vm.setMobileData(true)

        val shown = vm.uiState.first { it.message != null }
        assertTrue(shown.message!!.startsWith("Couldn't save"))
        assertEquals(false, retriever.prefs.value.allowMobileData)
        vm.messageShown()
        assertEquals(null, vm.uiState.first { it.message == null }.message)
    }

    @Test fun freeingUpSpaceSaysSoOnceThenClears() = runTest {
        val (vm, _) = viewModel()

        vm.freeUpSpace()

        assertEquals("Model removed", vm.uiState.first { it.message != null }.message)
        vm.messageShown()
        assertEquals(null, vm.uiState.first { it.message == null }.message)
    }
}
