package app.retriever.browse

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.retriever.core.CoreDispatchers
import app.retriever.core.Kind
import app.retriever.duplicates.retrieverOver
import app.retriever.nav.BrowseRoute
import app.retriever.starred.Starring
import app.retriever.testFile
import app.retriever.testRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BrowseViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private fun args(kind: String? = "PDF", sort: String = "NEWEST", starredOnly: Boolean = false) =
        SavedStateHandle(mapOf("kind" to kind, "folder" to null, "sort" to sort, "title" to "PDFs", "starredOnly" to starredOnly))

    private val files = listOf(testFile("b.pdf", Kind.PDF), testFile("a.pdf", Kind.PDF), testFile("c.jpg", Kind.PHOTO))

    @Test fun scrollingPausesReadingUntilTheGestureEnds() = runTest {
        var reads = 0
        val r = testRetriever(files, ramGb = 3.0, extract = {
            reads++
            emptyList()
        }, dispatchers = CoreDispatchers(StandardTestDispatcher(testScheduler)))
        val vm = BrowseViewModel(r, args())
        vm.setScrolling(true)
        val batch = launch { r.indexBatch(1.minutes) }
        runCurrent()
        assertFalse(batch.isCompleted)
        assertEquals(0, reads)
        vm.setScrolling(false)
        advanceUntilIdle()
        assertTrue(batch.isCompleted)
        assertTrue(reads > 0)
    }

    @Test fun leavingBrowseReleasesItsScrollGate() = runTest {
        val r = testRetriever(files, ramGb = 3.0, dispatchers = CoreDispatchers(StandardTestDispatcher(testScheduler)))
        val vm = BrowseViewModel(r, args())
        val store = ViewModelStore().apply { put("browse", vm) }
        vm.setScrolling(true)
        val batch = launch { r.indexBatch(1.minutes) }
        runCurrent()
        assertFalse(batch.isCompleted)
        store.clear()
        advanceUntilIdle()
        assertTrue(batch.isCompleted)
    }

    @Test fun loadsTheRouteFromTheSavedStateOnCreation() = runTest {
        val retriever = testRetriever(files).also { it.indexBatch(1.minutes) }
        val vm = BrowseViewModel(retriever, args())
        assertTrue(vm.uiState.value.loading)
        val loaded = vm.uiState.first { it.files.isNotEmpty() }
        assertEquals(BrowseRoute(kind = "PDF", title = "PDFs"), vm.route)
        assertEquals(setOf("a.pdf", "b.pdf"), loaded.files.map { it.file.name }.toSet())
        assertFalse(loaded.loading)
        assertTrue(loaded.exhausted)
    }

    @Test fun sortSurvivesRecreationAndRestartsPaging() = runTest {
        val retriever = testRetriever(files).also { it.indexBatch(1.minutes) }
        val saved = args()
        val first = BrowseViewModel(retriever, saved)
        first.uiState.first { it.files.isNotEmpty() }
        first.setSort("NAME")
        assertEquals(listOf("a.pdf", "b.pdf"), first.uiState.first { it.files.size == 2 && it.files[0].file.name == "a.pdf" }.files.map { it.file.name })

        // A rotation or process death builds a new ViewModel over the same handle.
        val second = BrowseViewModel(retriever, saved)
        assertEquals("NAME", second.uiState.value.sort)
        assertEquals(listOf("a.pdf", "b.pdf"), second.uiState.first { it.files.isNotEmpty() }.files.map { it.file.name })
    }

    @Test fun failedPageShowsTheErrorAndRetryResumesPaging() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val retriever = retrieverOver(driver = driver, files = files).also { it.indexBatch(1.minutes) }
        driver.execute(null, "ALTER TABLE files RENAME TO files_away", 0)
        val vm = BrowseViewModel(retriever, args())

        val failed = vm.uiState.first { it.error != null }
        assertEquals("Couldn't load these files.", failed.error)
        assertTrue(failed.files.isEmpty())
        assertFalse(failed.loading)

        driver.execute(null, "ALTER TABLE files_away RENAME TO files", 0)
        vm.retry()
        val recovered = vm.uiState.first { it.files.isNotEmpty() }
        assertNull(recovered.error)
        assertEquals(setOf("a.pdf", "b.pdf"), recovered.files.map { it.file.name }.toSet())
    }

    @Test fun starredListFiltersInTheViewModel() = runTest {
        val retriever = testRetriever(files).also { it.indexBatch(1.minutes) }
        retriever.setStarred("content://test/a.pdf", true)
        retriever.setStarred("content://test/b.pdf", true)
        val vm = BrowseViewModel(retriever, args(kind = null, starredOnly = true))
        assertEquals(setOf("a.pdf", "b.pdf"), vm.uiState.first { it.files.size == 2 }.files.map { it.file.name }.toSet())

        vm.stars.set("content://test/a.pdf", false)
        assertEquals(listOf("b.pdf"), vm.uiState.first { it.files.size == 1 }.files.map { it.file.name })
    }

    @Test fun starToggleFailureLandsInState() = runTest {
        val stars = Starring(MutableStateFlow(emptySet()), { _, _ -> error("disk full") }, backgroundScope)
        stars.set("content://test/a.pdf", true)
        assertTrue(stars.failed.first { it })
        stars.failureShown()
        assertFalse(stars.failed.value)
    }

    @Test fun aYearCardListsOnlyThePhotosAndVideosOfItsDay() = runTest {
        val day = 86_400_000L
        val start = 1_000 * day
        fun at(name: String, kind: Kind, t: Long) = testFile(name, kind).copy(modifiedAt = t, takenAt = t)
        val retriever = testRetriever(
            listOf(
                at("late.jpg", Kind.PHOTO, start + day - 1),
                at("clip.mp4", Kind.VIDEO, start + 5),
                at("early.jpg", Kind.PHOTO, start),
                at("before.jpg", Kind.PHOTO, start - 1),
                at("after.jpg", Kind.PHOTO, start + day),
                at("shot.png", Kind.SCREENSHOT, start + 10),
                testFile("noDate.jpg", Kind.PHOTO).copy(modifiedAt = start + 20),
            ),
        ).also { it.indexBatch(1.minutes) }
        val saved = SavedStateHandle(
            mapOf(
                "kind" to null,
                "folder" to null,
                "sort" to "NEWEST",
                "title" to "17 October 2023",
                "starredOnly" to false,
                "takenFrom" to start,
                "takenTo" to start + day - 1,
            ),
        )
        val vm = BrowseViewModel(retriever, saved)
        val loaded = vm.uiState.first { it.files.isNotEmpty() }
        assertEquals(listOf("early.jpg", "clip.mp4", "late.jpg"), loaded.files.map { it.file.name })
        assertTrue(loaded.exhausted)
        assertEquals("17 October 2023", browseTitle(vm.route, loaded.sort, allFiles = true))
    }
}
