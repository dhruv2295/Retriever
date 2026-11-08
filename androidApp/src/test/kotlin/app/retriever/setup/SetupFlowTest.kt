package app.retriever.setup

import android.app.AppOpsManager
import android.app.Application
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import app.retriever.access.Access
import app.retriever.access.AccessService
import app.retriever.core.CoreDispatchers
import app.retriever.core.DeviceWords
import app.retriever.core.Kind
import app.retriever.core.Part
import app.retriever.core.Piece
import app.retriever.core.ReadingPhase
import app.retriever.core.ReadingPolicy
import app.retriever.core.Retriever
import app.retriever.core.SetupMark
import app.retriever.core.SetupStage
import app.retriever.core.StopReason
import app.retriever.core.UpgradeStage
import app.retriever.indexing.IndexService
import app.retriever.model.ModelDownload
import app.retriever.model.ModelRepository
import app.retriever.model.UpgradeFlow
import app.retriever.testFile
import app.retriever.testRetriever
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import java.io.File

/** Android's side of core's setup machine: the answers it records and the steps it runs, with no model download. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SetupFlowTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        ShadowEnvironment.addExternalDir("sdcard")
        shadowOf(app).grantPermissions(*Access.mediaPermissions(35).toTypedArray())
    }

    @After fun tearDown() {
        WorkManager.getInstance(app).cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    // More than the demo takes, so something is left for the choice.
    private val docs = (1..40).map { testFile("doc$it.txt", Kind.DOC) }

    /** An ordinary phone with no model: reading needs none. */
    private fun TestScope.noModel(): Retriever = testRetriever(
        docs,
        dispatchers = CoreDispatchers(StandardTestDispatcher(testScheduler)),
        extract = { listOf(Piece("", listOf(Part.Text("read content")), "read content", "read content")) },
    ).apply { uiVisible = true }

    private fun flow(r: Retriever, scope: CoroutineScope, startDownload: () -> Unit = {}) =
        SetupFlow(r, AccessService(app), IndexService(app, r), scope, notificationsGranted = { false }, startDownload = startDownload)

    private fun denyAccess() {
        shadowOf(app).denyPermissions(*Access.mediaPermissions(35).toTypedArray())
        shadowOf(app.getSystemService(AppOpsManager::class.java))
            .setMode("android:manage_external_storage", Process.myUid(), app.packageName, AppOpsManager.MODE_ERRORED)
    }

    @Test fun aFreshInstallSaysHelloEvenWithAccessGranted() = runTest {
        val r = noModel()
        r.refreshSetUp()
        val ui = flow(r, backgroundScope).ui.value
        assertTrue(ui.access.ready)
        // Access is confirmed on its own screen, never assumed.
        assertEquals(SetupStage.WELCOME, ui.stage)
        assertTrue(ui.setup.showSetup)
    }

    /** Get started leads to the access screen and downloads the model once; Back returns to Hello. */
    @Test fun getStartedDownloadsAndLeadsToAccess() = runTest {
        val r = noModel()
        r.refreshSetUp()
        var downloads = 0
        val f = flow(r, backgroundScope) { downloads++ }
        assertTrue(f.ui.value.downloadsOnGetStarted)
        f.getStarted()
        f.ui.first { it.stage == SetupStage.ACCESS }
        assertEquals(1, downloads)
        f.back()
        f.ui.first { it.stage == SetupStage.WELCOME }
    }

    @Test fun getStartedOnAPhoneTooSmallForTheModelDownloadsNothing() = runTest {
        val r = testRetriever(docs, ramGb = 3.0, dispatchers = CoreDispatchers(StandardTestDispatcher(testScheduler)))
        r.refreshSetUp()
        var downloads = 0
        val f = flow(r, backgroundScope) { downloads++ }
        assertFalse(f.ui.value.downloadsOnGetStarted)
        f.getStarted()
        f.ui.first { it.stage == SetupStage.ACCESS }
        assertEquals(0, downloads)
    }

    /** Nothing granted keeps the person on the access screen: there is no way past it without access. */
    @Test fun withoutAccessContinueStaysOnTheAccessScreen() = runTest {
        denyAccess()
        val r = noModel()
        r.refreshSetUp()
        val f = flow(r, backgroundScope)
        assertFalse(f.ui.value.access.ready)
        f.getStarted()
        f.ui.first { it.stage == SetupStage.ACCESS }
        f.continueFromAccess()
        r.setup.first { it?.mark == SetupMark.ACCESS_CHOSEN }
        assertEquals(SetupStage.ACCESS, f.ui.value.stage)
        assertTrue(f.ui.value.setup.showSetup)
    }

    /** All-files access already on: the access screen's Continue confirms it, survives the process, and the first search plans. */
    @Test fun continueFromTheWelcomeWithAccessInPlaceSurvivesTheProcess() = runTest {
        val r = noModel()
        r.refreshSetUp()
        flow(r, backgroundScope).continueFromAccess()
        r.setup.first { it?.mark == SetupMark.ACCESS_CHOSEN }
        r.refreshSetUp()
        assertEquals(SetupStage.SAMPLE, flow(r, backgroundScope).ui.value.stage)
    }

    @Test fun continueFromAccessPlansTheDemoAndProcessesItWithoutAModel() = runTest {
        val r = noModel()
        r.refreshSetUp()
        val f = flow(r, backgroundScope)
        f.continueFromAccess()
        val sample = f.ui.first { it.stage == SetupStage.SAMPLE && it.setup.planned > 0 }
        assertFalse(sample.setup.decided)
        assertEquals("the reader is woken for it", 1, WorkManager.getInstance(app).getWorkInfosForUniqueWork(IndexService.INDEX).get().size)
        r.taste() // what the reader runs
        val p = r.tasteProgress().first { it.planned > 0 && it.pending == 0 }
        assertEquals(p.planned, p.done)
        // Every planned file in plan order, the read ones first.
        val files = f.ui.first { s -> s.sample.orEmpty().any { it.read } }.sample!!
        assertEquals(sample.setup.planned, files.size)
        assertEquals(files.indices.toList(), files.map { it.index })
    }

    /** The first search's Continue ends setup for good and asks Home for its one-time card; Back no longer applies. */
    @Test fun finishEndsSetupAndAsksHomeForItsCard() = runTest {
        val r = noModel()
        r.refreshSetUp()
        val f = flow(r, backgroundScope)
        f.continueFromAccess()
        f.ui.first { it.stage == SetupStage.SAMPLE }
        f.finish()
        f.ui.first { !it.setup.showSetup }
        assertTrue(r.prefs.value.homeIntroDue)
        f.back()
        r.refreshSetUp()
        assertEquals(SetupStage.DONE, flow(r, backgroundScope).ui.value.stage)
    }

    /** Setup shows a stop's reason only: "Up to date" or "Checking for new files…" is no pause. */
    @Test fun onlyAStopReachesTheDemoAsAPause() = runTest {
        val r = noModel()
        r.refreshSetUp()
        val f = flow(r, backgroundScope)
        // Counted first: until the phone's files are, the line says "Counting your files…", which is no stop either.
        r.planTaste()
        r.pause()
        assertEquals(ReadingPolicy.stopCopy(StopReason.PAUSED, DeviceWords.ANDROID), f.ui.first { it.pauseReason != null }.pauseReason)
        r.resume()
        // Resumed with nothing to read: a quiet phase with a title, which the old rule showed as a pause.
        val quiet = r.readingStatus().first { it.phase != ReadingPhase.PAUSED }
        assertNull(quiet.pause)
        f.ui.first { it.pauseReason == null }
    }

    /** The first search's progress and files are core's shared flows: watched only while a screen collects. */
    @Test fun theLibraryIsWatchedOnlyWhileAScreenCollects() = runTest {
        val r = noModel()
        r.refreshSetUp()
        val f = flow(r, backgroundScope)
        advanceUntilIdle()
        assertNull("watched with no screen", f.ui.value.taste)
        assertNotNull(f.ui.first { it.taste != null })
    }

    @Test fun theUpgradeCountsOnlyWhileAScreenCollects() = runTest {
        val r = noModel()
        r.refreshSetUp()
        val u = upgrade(r, backgroundScope, metered = false)
        advanceUntilIdle()
        assertNull("counted with no screen", u.ui.value.progress)
        assertNotNull(u.ui.first { it.progress != null })
    }

    private fun upgrade(r: Retriever, scope: CoroutineScope, metered: Boolean): UpgradeFlow {
        val download = ModelDownload(app, File(app.filesDir, "models"))
        return UpgradeFlow(
            r,
            ModelRepository(app, download, r, scope),
            AccessService(app),
            IndexService(app, r),
            scope,
            onMeteredNetwork = { metered },
            calibrate = { error("no tuning in this test") },
        )
    }

    @Test fun withNoModelTheUpgradeIsOfferedAndMobileDataAsksFirst() = runTest {
        val r = noModel()
        r.refreshSetUp()
        val u = upgrade(r, backgroundScope, metered = true)
        u.ui.first { it.stage == UpgradeStage.OFFER }
        u.download()
        assertEquals(UpgradeStage.OFFER, u.ui.first { it.askMobileData }.stage)
    }

    @Test fun aPhoneTooSmallForTheModelHasNoUpgrade() = runTest {
        val r = testRetriever(ramGb = 3.0, dispatchers = CoreDispatchers(StandardTestDispatcher(testScheduler)))
        r.refreshSetUp()
        assertEquals(UpgradeStage.UNAVAILABLE, upgrade(r, backgroundScope, metered = false).ui.first { it.progress != null }.stage)
    }
}
