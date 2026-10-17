package app.retriever

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.retriever.core.Backend
import app.retriever.core.Changes
import app.retriever.core.ContentHasher
import app.retriever.core.DeviceMonitor
import app.retriever.core.DeviceProbe
import app.retriever.core.DeviceProfile
import app.retriever.core.DeviceState
import app.retriever.core.Embedder
import app.retriever.core.EmbedderFactory
import app.retriever.core.Extractor
import app.retriever.core.FileSource
import app.retriever.core.Kind
import app.retriever.core.ModelBuild
import app.retriever.core.Piece
import app.retriever.core.Platform
import app.retriever.core.Retriever
import app.retriever.core.SourceFile
import app.retriever.core.Store
import app.retriever.core.Thermal
import app.retriever.core.db.IndexDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
import kotlin.time.Duration.Companion.minutes

/** A real Retriever over an in-memory store with no model, so search answers from file names. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SearchViewModelTest {
    // Long PDF names rank below the short photo names, so a mixed top 60 holds few or no PDFs.
    private val pdfs = (1..3).map { file("bill from the electricity board for month $it.pdf", Kind.PDF) }
    private val photos = (1..120).map { file("bill $it.jpg", Kind.PHOTO) }

    private fun file(name: String, kind: Kind) = SourceFile("content://test/$name", name, "", "application/octet-stream", kind, 100, 1)

    private val retriever = Retriever(
        store = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).let { IndexDb.Schema.create(it); Store(it) },
        source = object : FileSource {
            override suspend fun changes(since: String?) = Changes(pdfs + photos, emptyList(), "tok", isFullScan = true)
        },
        extractor = object : Extractor {
            override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> = emptyList()
        },
        factory = object : EmbedderFactory {
            override fun backends(build: ModelBuild) = listOf(Backend.CPU)
            override fun open(profile: DeviceProfile, build: ModelBuild): Embedder = error("no model in this test")
        },
        monitor = object : DeviceMonitor {
            override fun state() = DeviceState(8.0, true, 80, Thermal.NONE)
        },
        hasher = object : ContentHasher {
            override suspend fun quickHash(uri: String, size: Long): String? = null
            override suspend fun fullHash(uri: String): String? = null
        },
        probe = DeviceProbe(Platform.ANDROID, 35, "Google", "Pixel", 8.0, false),
        modelReady = { false },
        platformReady = { true },
        log = { _, _ -> },
        now = System::currentTimeMillis,
    )

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun chipSearchesInsideItsKind() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = SearchViewModel(retriever) { null }
        vm.setQuery("bill")
        val mixed = vm.ui.first { it.hits?.size == 60 }.hits!!
        vm.setChip(Kind.PDF)
        val pdfHits = vm.ui.first { ui -> ui.chip == Kind.PDF && !ui.searching && ui.hits.orEmpty().all { it.file.kind == Kind.PDF } }.hits!!
        assertEquals(pdfs.map { it.name }.toSet(), pdfHits.map { it.file.name }.toSet())
        // A filter over the mixed results could not have found them all.
        assertTrue(mixed.count { it.file.kind == Kind.PDF } < pdfs.size)
    }

    @Test fun hitsSayWhichWordsTheyAnswerAndClearingTheWordsDropsTheFilter() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = SearchViewModel(retriever) { null }
        vm.setQuery("bill")
        vm.setChip(Kind.PDF)
        assertEquals("bill", vm.ui.first { it.hits != null && !it.searching }.hitsFor)
        vm.setQuery("")
        assertEquals(null, vm.ui.value.chip)
        // An empty field that stays empty (the screen coming back) keeps a filter picked since.
        vm.setChip(Kind.PHOTO)
        vm.setQuery("")
        assertEquals(Kind.PHOTO, vm.ui.value.chip)
    }

    @Test fun openingAHitAfterTypingRecordsTheQueryOnce() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = SearchViewModel(retriever) { null }
        vm.setQuery("bill")
        val hit = vm.ui.first { it.hits?.size == 60 }.hits!!.first()
        vm.opened(hit)
        assertEquals(listOf("bill"), vm.ui.first { it.recent.isNotEmpty() }.recent)
        vm.opened(hit)
        advanceUntilIdle()
        assertEquals(listOf("bill"), retriever.recentQueries())
    }

    @Test fun openingAHitDuringAPhotoSearchRecordsNothing() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = SearchViewModel(retriever) { ByteArray(1) }
        vm.setQuery("bill")
        val hit = vm.ui.first { it.hits?.size == 60 }.hits!!.first()
        vm.findSimilar(photos.first()).join()
        assertTrue(vm.ui.value.similar != null)
        vm.opened(hit)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), retriever.recentQueries())
    }

    @Test fun openingAHitWithABlankQueryRecordsNothing() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = SearchViewModel(retriever) { null }
        vm.setQuery("bill")
        val hit = vm.ui.first { it.hits?.size == 60 }.hits!!.first()
        vm.setQuery("   ")
        vm.opened(hit)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), retriever.recentQueries())
    }

    @Test fun recordsOnlyOnSubmitAndKeepsHistoryInTheStore() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = SearchViewModel(retriever) { null }
        vm.setQuery("bill")
        advanceTimeBy(1_000)
        vm.ui.first { it.hits != null }
        assertEquals(emptyList<String>(), retriever.recentQueries())

        vm.submit()
        vm.ui.first { "bill" in it.recent }

        val again = SearchViewModel(retriever) { null }
        assertEquals(listOf("bill"), again.ui.first { it.recent.isNotEmpty() }.recent)

        again.removeRecent("bill").join()
        assertEquals(emptyList<String>(), again.ui.value.recent)
        assertEquals(emptyList<String>(), retriever.recentQueries())
    }
}
