package app.retriever.setup

import android.app.AppOpsManager
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.retriever.access.Access
import app.retriever.access.AccessService
import app.retriever.access.Photos
import app.retriever.access.PickedFolder
import app.retriever.core.Backend
import app.retriever.core.CoreDispatchers
import app.retriever.core.DeviceProfile
import app.retriever.core.Kind
import app.retriever.core.ModelVariant
import app.retriever.core.Retriever
import app.retriever.core.TasteProgress
import app.retriever.core.Tier
import app.retriever.indexing.IndexService
import app.retriever.model.ModelDownload
import app.retriever.model.ModelRepository
import app.retriever.model.ModelState
import app.retriever.model.TuneState
import app.retriever.setup.SetupStep.ASK_FILES
import app.retriever.setup.SetupStep.ASK_MOBILE_DATA
import app.retriever.setup.SetupStep.COMMIT
import app.retriever.setup.SetupStep.DOWNLOAD
import app.retriever.setup.SetupStep.NEEDS_SPACE
import app.retriever.setup.SetupStep.NONE
import app.retriever.setup.SetupStep.READ_FEW
import app.retriever.setup.SetupStep.SCHEDULE
import app.retriever.setup.SetupStep.SYNC_NAMES
import app.retriever.setup.SetupStep.TRY
import app.retriever.setup.SetupStep.TUNE
import app.retriever.setup.SetupStep.VERIFY
import app.retriever.testFile
import app.retriever.testRetriever
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SetupStepTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val download = ModelDownload(app, File(app.filesDir, "models"))

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        // Environment.isExternalStorageManager() reads the primary external volume, which Robolectric starts without.
        ShadowEnvironment.addExternalDir("sdcard")
    }

    private fun TestScope.core() = CoreDispatchers(StandardTestDispatcher(testScheduler))

    private fun flow(r: Retriever, scope: CoroutineScope) = SetupFlow(
        r, ModelRepository(app, download, r, scope), AccessService(app), IndexService(app, r), download, scope,
        onMeteredNetwork = { false }, notificationsGranted = { false }, calibrate = { error("no tuning in this test") },
    )

    @Test fun setupStartedPersistsThroughPrefs() = runTest {
        val r = testRetriever(dispatchers = core())
        r.refreshSetUp()
        val f = flow(r, backgroundScope)
        assertFalse(f.ui.value.started)
        f.getStarted()
        f.dismissNotificationAsk()
        r.prefs.first { it.setupStarted && it.notificationsDismissed }
        // A new process reads both back from the store.
        r.refreshSetUp()
        assertTrue(flow(r, backgroundScope).ui.value.started)
    }

    /** An NPU-build fallback after setup leaves setUp true with the model missing: Setup offers the download. */
    @Test fun modelGoneAfterSetupOffersTheDownload() = runTest {
        shadowOf(app).grantPermissions(*Access.mediaPermissions(35).toTypedArray())
        val r = testRetriever(profile = DeviceProfile(Tier.BALANCED, ModelVariant.M740, Backend.CPU, 70, 5, false, false), dispatchers = core())
        r.refreshSetUp()
        assertEquals(true, r.setUp.value)
        assertTrue(r.modelMissing.value)
        val f = flow(r, backgroundScope)
        f.refresh()
        val ui = f.ui.first { it.namesSynced }
        assertTrue(ui.started)
        assertFalse("Setup stays open instead of handing off to Home", ui.ready)
        assertTrue("the Download button is offered", ui.downloadStopped)
        // Offered, not fetched unasked: Free up space looks the same from here.
        assertEquals(NONE, nextStep(ui, hasSpace = true, metered = false, profileExists = true))
        assertEquals(DOWNLOAD, nextStep(ui.copy(downloadStopped = false), hasSpace = true, metered = false, profileExists = true))
    }

    /** Get started tapped, files allowed, names synced: the model is next. */
    private val going = SetupUi(started = true, access = Access.ALL, namesSynced = true)

    private fun next(ui: SetupUi, hasSpace: Boolean = true, metered: Boolean = false, profileExists: Boolean = false) =
        nextStep(ui, hasSpace, metered, profileExists)

    @Test fun freshInstallWaitsForGetStarted() = assertEquals(NONE, next(SetupUi()))

    /** The welcome tap: the download needs no file access, so it is queued before access is asked for. */
    @Test fun welcomeTapDownloadsBeforeAskingForFiles() {
        val tapped = SetupUi(started = true)
        assertEquals(DOWNLOAD, next(tapped))
        assertEquals(ASK_FILES, next(tapped.copy(model = ModelState.Downloading(0f, true))))
        assertEquals(ASK_FILES, next(tapped.copy(model = ModelState.WaitingForWifi)))
        assertEquals(SetupStage.ACCESS, tapped.copy(model = ModelState.Downloading(0f, true)).stage)
    }

    @Test fun meteredAsksBeforeAnything() {
        val tapped = SetupUi(started = true)
        assertEquals(ASK_MOBILE_DATA, next(tapped, metered = true))
        // The question sits over the welcome, so access comes after it.
        assertEquals(SetupStage.WELCOME, tapped.copy(askMobileData = true).stage)
        assertEquals(DOWNLOAD, next(tapped.copy(allowMetered = false), metered = true))
    }

    @Test fun filesGrantedFirstTimeSyncsNames() =
        assertEquals(SYNC_NAMES, next(SetupUi(started = true, access = Access.ALL, model = ModelState.Downloading(0.2f, false))))

    private fun progress(read: Int, of: Int) =
        TasteProgress(mapOf(Kind.DOC to (read to of)), if (read == of) setOf(Kind.DOC) else emptySet(), read, 30, read >= 20 || read == of)

    /** Ready with a plan the person hasn't answered: the short read, then try-it, then the commit choice. */
    @Test fun aPlannedShortReadRunsThroughItsStages() {
        val reading = going.copy(model = ModelState.Done, ready = true, decided = false, taste = progress(5, 40))
        assertEquals(READ_FEW, next(reading, profileExists = true))
        assertEquals(SetupStage.READ_FEW, reading.stage)
        assertEquals(TRY, next(reading.copy(trying = true), profileExists = true))
        assertEquals(SetupStage.TRY, reading.copy(trying = true).stage)
        assertEquals(COMMIT, next(reading.copy(trying = true, committing = true), profileExists = true))
        // Decided (or Skip): Home, and reading carries on under the scheduled work.
        assertEquals(SCHEDULE, next(reading.copy(decided = true), profileExists = true))
        assertEquals(SetupStage.DONE, reading.copy(decided = true).stage)
        assertEquals(SetupStage.DONE, reading.copy(skipped = true).stage)
        // The plan exists from the names sync on, but the short read waits for the download and the tune.
        assertEquals(SetupStage.WAITING, reading.copy(ready = false, model = ModelState.Downloading(0.5f, false)).stage)
    }

    private val docs = (1..30).map { testFile("doc$it.txt", Kind.DOC) }

    /** A words-only phone needs no download or tune, so its whole first run plays out here. */
    private fun TestScope.wordsOnly(): Retriever {
        shadowOf(app).grantPermissions(*Access.mediaPermissions(35).toTypedArray())
        return testRetriever(docs, ramGb = 3.0, dispatchers = core())
    }

    @Test fun tryingFirstPlansAtTheNamesSyncAndResumesAfterProcessDeath() = runTest {
        val r = wordsOnly()
        r.refreshSetUp()
        val f = flow(r, backgroundScope)
        f.getStarted()
        val reading = f.ui.first { it.scheduled }
        assertTrue(reading.firstRun)
        assertFalse(r.tasteDecided())
        val plan = r.tasteProgress().first { it.readyToTry }
        // A new process: the stage comes back from core's meta, past "Try it now" as the person left it, plan unchanged.
        r.refreshSetUp()
        val again = flow(r, backgroundScope)
        again.refresh()
        val resumed = again.ui.first { it.namesSynced && it.taste != null }
        assertEquals(SetupStage.TRY, resumed.stage)
        assertEquals(plan.byKind.mapValues { it.value.second }, r.tasteProgress().first().byKind.mapValues { it.value.second })
    }

    @Test fun readEverythingNeverSeesTryOrCommit() = runTest {
        val r = wordsOnly()
        r.refreshSetUp()
        val f = flow(r, backgroundScope)
        f.getStarted(readEverything = true)
        val ui = f.ui.first { it.scheduled }
        assertEquals(SetupStage.DONE, ui.stage)
        assertTrue(r.tasteDecided())
        assertTrue(r.tasteProgress().first().byKind.isEmpty())
    }

    /** Set up before the first run existed: no taste_scope, never a short read. */
    @Test fun aReturningUserNeverSeesTheShortRead() = runTest {
        val r = wordsOnly()
        r.refreshSetUp()
        val f = flow(r, backgroundScope)
        f.refresh()
        val ui = f.ui.first { it.scheduled }
        assertEquals(SetupStage.DONE, ui.stage)
        assertTrue(r.tasteProgress().first().byKind.isEmpty())
    }

    @Test fun theWelcomeTapQueuesTheDownloadWithoutFileAccess() = runTest {
        shadowOf(app).denyPermissions(*Access.mediaPermissions(35).toTypedArray())
        // Robolectric allows every app op, so all-files access reads as granted until refused here.
        shadowOf(app.getSystemService(AppOpsManager::class.java))
            .setMode("android:manage_external_storage", android.os.Process.myUid(), app.packageName, AppOpsManager.MODE_ERRORED)
        val r = testRetriever(dispatchers = core())
        r.refreshSetUp()
        val f = flow(r, backgroundScope)
        f.getStarted()
        // Queued, or refused for space: either way the download was asked for before any file access.
        val ui = f.ui.first { it.model != ModelState.Missing }
        assertFalse(ui.files)
        assertEquals(SetupStage.ACCESS, ui.stage)
    }

    @Test fun noSpace() = assertEquals(NEEDS_SPACE, next(going, hasSpace = false))

    @Test fun unmeteredDownloadsStraightAway() = assertEquals(DOWNLOAD, next(going))

    @Test fun meteredUnansweredAsks() = assertEquals(ASK_MOBILE_DATA, next(going, metered = true))

    @Test fun meteredWaitForWifiStillQueuesTheDownload() = assertEquals(DOWNLOAD, next(going.copy(allowMetered = false), metered = true))

    @Test fun stoppedDownloadWaitsForTheUser() = assertEquals(NONE, next(going.copy(downloadStopped = true)))

    @Test fun downloadingDoesNothing() = assertEquals(NONE, next(going.copy(model = ModelState.Downloading(0.4f, false))))

    @Test fun fileInPlaceIsChecked() = assertEquals(VERIFY, next(going.copy(model = ModelState.Checking)))

    @Test fun failedDownloadWaitsForTryAgain() = assertEquals(NONE, next(going.copy(model = ModelState.Failed("x"))))

    @Test fun downloadDoneNoProfileTunes() = assertEquals(TUNE, next(going.copy(model = ModelState.Done)))

    @Test fun tuneFailedDoesNotLoop() = assertEquals(NONE, next(going.copy(model = ModelState.Done, tune = TuneState.Failed("x"))))

    @Test fun tuneRunningDoesNotStartAnother() = assertEquals(NONE, next(going.copy(model = ModelState.Done, tune = TuneState.Running)))

    @Test fun readySchedulesOnce() {
        val ready = going.copy(model = ModelState.Done, ready = true)
        assertEquals(SCHEDULE, next(ready, profileExists = true))
        assertEquals(NONE, next(ready.copy(scheduled = true), profileExists = true))
    }

    @Test fun canSearchOnceStartedWithFiles() {
        assertEquals(false, SetupUi(started = true).canSearch)
        assertEquals(true, SetupUi(started = true, access = Access.ALL).canSearch)
        // The narrower path is enough to start: only picked photos, or one folder.
        assertEquals(true, SetupUi(started = true, access = Access(false, Photos.SELECTED)).canSearch)
        assertEquals(true, SetupUi(started = true, access = Access(false, folders = listOf(PickedFolder("u", "Docs")))).canSearch)
    }

    @Test fun textOnlyNeverDownloadsOrTunes() {
        val textOnly = going.copy(textOnly = true)
        assertEquals(SYNC_NAMES, next(textOnly.copy(namesSynced = false)))
        assertEquals(NONE, next(textOnly))
        assertEquals(NONE, next(textOnly, metered = true, hasSpace = false))
        assertEquals(NONE, next(textOnly.copy(model = ModelState.Done)))
        assertEquals(SCHEDULE, next(textOnly.copy(ready = true)))
    }
}
