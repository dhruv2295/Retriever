package app.retriever

import androidx.lifecycle.SavedStateHandle
import app.retriever.core.Kind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BrowseViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private fun args(kind: String? = "PDF", sort: String = "NEWEST") =
        SavedStateHandle(mapOf("kind" to kind, "folder" to null, "sort" to sort, "title" to "PDFs", "starredOnly" to false))

    private val files = listOf(testFile("b.pdf", Kind.PDF), testFile("a.pdf", Kind.PDF), testFile("c.jpg", Kind.PHOTO))

    @Test fun loadsTheRouteFromTheSavedStateOnCreation() = runTest {
        val retriever = testRetriever(files).also { it.indexBatch(1.minutes) }
        val vm = BrowseViewModel(retriever, args())
        val loaded = vm.files.first { it.isNotEmpty() }
        assertEquals(BrowseRoute(kind = "PDF", title = "PDFs"), vm.route)
        assertEquals(setOf("a.pdf", "b.pdf"), loaded.map { it.file.name }.toSet())
        assertFalse(vm.loading.first { !it })
    }

    @Test fun sortSurvivesRecreationAndRestartsPaging() = runTest {
        val retriever = testRetriever(files).also { it.indexBatch(1.minutes) }
        val saved = args()
        val first = BrowseViewModel(retriever, saved)
        first.files.first { it.isNotEmpty() }
        first.setSort("NAME")
        assertEquals(listOf("a.pdf", "b.pdf"), first.files.first { it.size == 2 && it[0].file.name == "a.pdf" }.map { it.file.name })

        // A rotation or process death builds a new ViewModel over the same handle.
        val second = BrowseViewModel(retriever, saved)
        assertEquals("NAME", second.sort.value)
        assertEquals(listOf("a.pdf", "b.pdf"), second.files.first { it.isNotEmpty() }.map { it.file.name })
    }

    @Test fun starToggleFailureLandsInState() = runTest {
        val stars = Starring(MutableStateFlow(emptySet()), { _, _ -> error("disk full") }, backgroundScope)
        stars.set("content://test/a.pdf", true)
        assertTrue(stars.failed.first { it })
        stars.failureShown()
        assertFalse(stars.failed.value)
    }
}
