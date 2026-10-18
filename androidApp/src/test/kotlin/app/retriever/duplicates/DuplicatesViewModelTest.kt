package app.retriever.duplicates

import android.app.Application
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.retriever.MainActivity
import app.retriever.NoFileAccess
import app.retriever.access.AccessService
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
import app.retriever.core.android.SafSource
import app.retriever.core.db.IndexDb
import app.retriever.indexing.IndexService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import kotlin.time.Duration.Companion.minutes

/** A real Retriever on [driver] listing [files] once; every file hashes alike unless [hasher] says otherwise. */
internal fun retrieverOver(
    driver: JdbcSqliteDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY),
    files: List<SourceFile> = emptyList(),
    hasher: ContentHasher = object : ContentHasher {
        override suspend fun quickHash(uri: String, size: Long): String? = "same"
        override suspend fun fullHash(uri: String): String? = "same"
    },
): Retriever {
    IndexDb.Schema.create(driver)
    return Retriever(
        store = Store(driver),
        source = object : FileSource {
            override suspend fun changes(since: String?) = Changes(files, emptyList(), "tok", isFullScan = true)
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
        hasher = hasher,
        probe = DeviceProbe(Platform.ANDROID, 35, "Google", "Pixel", 8.0, false),
        modelReady = { false },
        platformReady = { true },
        log = { _, _ -> },
        now = System::currentTimeMillis,
        zoneOffsetMs = { 0L },
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DuplicatesViewModelTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val older = copy("a.jpg", modifiedAt = 1)
    private val newer = copy("b.jpg", modifiedAt = 2)

    private fun copy(name: String, modifiedAt: Long) =
        SourceFile("${SafSource.PREFIX}tree/x/document/$name", name, "/Pictures", "image/jpeg", Kind.PHOTO, 70_000, modifiedAt)

    private fun viewModel(retriever: Retriever) = DuplicatesViewModel(
        retriever,
        IndexService(app, retriever),
        // Demo mode grants Access.ALL, which stands in for a phone with all-files access; Robolectric cannot answer Environment.isExternalStorageManager().
        AccessService(app, demo = true),
        MediaDeleter(app),
    )

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    @Config(shadows = [NoFileAccess::class, DeleteRequestShadow::class])
    fun aConfirmationWaitsForACollectorAndIsDeliveredOnlyOnce() = runTest {
        val a = older.copy(uri = "content://media/external/images/media/1")
        val b = newer.copy(uri = "content://media/external/images/media/2")
        val retriever = retrieverOver(files = listOf(a, b))
        retriever.indexBatch(1.minutes)
        val vm = DuplicatesViewModel(retriever, IndexService(app, retriever), AccessService(app), MediaDeleter(app))
        vm.state.first { !it.scanning }
        vm.deleteSelected()
        DeleteRequestShadow.created.await()
        runCurrent()
        assertTrue(vm.state.value.deleting)
        assertEquals(listOf(Uri.parse(b.uri)), DeleteRequestShadow.uris)
        assertEquals(DeleteRequestShadow.request.intentSender, vm.confirm.first())
        val afterRecreation = backgroundScope.async { vm.confirm.first() }
        runCurrent()
        assertFalse(afterRecreation.isCompleted)
    }

    @Test fun scanKeepsTheOldestCopyAndGuardsTheLastOne() = runTest {
        val retriever = retrieverOver(files = listOf(older, newer))
        retriever.indexBatch(1.minutes)
        val vm = viewModel(retriever)
        val s = vm.state.first { !it.scanning }
        assertEquals(1, s.groups.size)
        assertEquals(setOf(newer.uri), s.selected)

        // Selecting the older copy too would delete every copy, so it is refused.
        vm.toggle(older.uri)
        assertTrue(vm.state.value.keepOne)
        assertEquals(setOf(newer.uri), vm.state.value.selected)
    }

    @Test fun scanFailureShowsAnErrorInsteadOfCrashing() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val retriever = retrieverOver(driver)
        driver.close()
        val s = viewModel(retriever).state.first { !it.scanning }
        // The body under the screen's "Couldn't look for duplicates" title, not that title again.
        assertEquals("Something went wrong. Try again.", s.error)
    }

    @Test fun confirmAfterTheSystemPromptRescansFromTheIndex() = runTest {
        val listed = mutableListOf(older, newer)
        val retriever = retrieverOver(files = listed)
        retriever.indexBatch(1.minutes)
        val vm = viewModel(retriever)
        val first = vm.state.first { !it.scanning }
        assertEquals(1, first.groups.size)
        assertEquals(retriever.fileCount(), first.scannedFiles)

        // The system deleted the newer copy; the sync it asked for has run.
        listed.remove(newer)
        retriever.indexBatch(1.minutes)
        vm.onConfirmed()

        assertTrue(vm.state.value.scanning)
        val after = vm.state.first { !it.scanning }
        assertTrue(after.groups.isEmpty())
        assertEquals(1L, after.scannedFiles)
    }

    @Test fun deleteThatCannotRunCountsAsFailedAndKeepsTheFiles() = runTest {
        val retriever = retrieverOver(files = listOf(older, newer))
        retriever.indexBatch(1.minutes)
        val vm = viewModel(retriever)
        vm.state.first { !it.scanning }
        vm.deleteSelected()
        val s = vm.state.first { !it.deleting }
        assertEquals(1, s.failed)
        assertEquals(setOf(newer.uri), s.selected)
    }
}

/** The system owns the confirmation activity; the ViewModel test only needs its pending request. */
@Implements(MediaStore::class)
class DeleteRequestShadow {
    companion object {
        lateinit var request: PendingIntent
        var uris: List<Uri> = emptyList()
        val created = CompletableDeferred<Unit>()

        @Implementation
        @JvmStatic
        fun createDeleteRequest(resolver: ContentResolver, requested: Collection<Uri>): PendingIntent {
            uris = requested.toList()
            val app = ApplicationProvider.getApplicationContext<Application>()
            return PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                .also {
                    request = it
                    created.complete(Unit)
                }
        }
    }
}
