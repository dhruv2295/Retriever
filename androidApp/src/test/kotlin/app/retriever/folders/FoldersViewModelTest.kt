package app.retriever.folders

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.retriever.core.Kind
import app.retriever.core.SourceFile
import app.retriever.duplicates.retrieverOver
import app.retriever.indexing.IndexService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
class FoldersViewModelTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private fun file(name: String, folder: String) = SourceFile("content://test/$folder/$name", name, folder, "image/jpeg", Kind.PHOTO, 100, 1)

    private val files = listOf(file("a.jpg", "/Pictures"), file("b.jpg", "/Pictures"), file("c.jpg", "/Download"))

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun listsFoldersAndExcludingOneHidesItAtOnce() = runTest {
        val retriever = retrieverOver(files = files)
        retriever.indexBatch(1.minutes)
        val vm = FoldersViewModel(retriever, IndexService(app, retriever))
        val loaded = vm.ui.first { !it.loading }
        assertEquals(mapOf("/Pictures" to 2L, "/Download" to 1L), loaded.folders.toMap())

        vm.exclude("/Pictures")
        val left = vm.ui.first { it.folders.size == 1 }
        assertEquals("/Download", left.folders.single().first)
        assertEquals(FoldersUi(left.folders, loading = false), left)
    }

    @Test fun anExcludeFailureWithFoldersOnScreenSurfacesTheError() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val retriever = retrieverOver(driver, files)
        retriever.indexBatch(1.minutes)
        val vm = FoldersViewModel(retriever, IndexService(app, retriever))
        vm.ui.first { !it.loading }
        driver.close()

        vm.exclude("/Pictures")

        val failed = vm.ui.first { it.error != null }
        assertEquals("Couldn't stop searching that folder. Try again in a moment.", failed.error)
        assertEquals(2, failed.folders.size)
        assertEquals(false, failed.loadFailed)
        vm.errorShown()
        assertEquals(null, vm.ui.value.error)
    }

    @Test fun loadFailureEndsLoadingWithAMessage() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val retriever = retrieverOver(driver)
        driver.close()
        val vm = FoldersViewModel(retriever, IndexService(app, retriever))
        val ui = vm.ui.first { !it.loading }
        assertNotNull(ui.error)
        assertTrue(ui.loadFailed)
        assertTrue(ui.folders.isEmpty())
    }
}
