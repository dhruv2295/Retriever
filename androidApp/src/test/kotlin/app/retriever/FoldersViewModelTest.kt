package app.retriever

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.retriever.core.Kind
import app.retriever.core.SourceFile
import app.retriever.data.IndexService
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
    private fun file(name: String, folder: String) =
        SourceFile("content://test/$folder/$name", name, folder, "image/jpeg", Kind.PHOTO, 100, 1)

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
        vm.loading.first { !it }
        assertEquals(mapOf("/Pictures" to 2L, "/Download" to 1L), vm.folders.value.toMap())

        vm.exclude("/Pictures")
        val left = vm.folders.first { it.size == 1 }
        assertEquals("/Download", left.single().first)
        assertTrue(vm.error.value == null)
    }

    @Test fun loadFailureEndsLoadingWithAMessage() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val retriever = retrieverOver(driver)
        driver.close()
        val vm = FoldersViewModel(retriever, IndexService(app, retriever))
        vm.loading.first { !it }
        assertNotNull(vm.error.value)
        assertTrue(vm.folders.value.isEmpty())
    }
}
