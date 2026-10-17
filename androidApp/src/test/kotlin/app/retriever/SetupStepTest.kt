package app.retriever

import app.retriever.SetupStep.ASK_FILES
import app.retriever.SetupStep.ASK_MOBILE_DATA
import app.retriever.SetupStep.DOWNLOAD
import app.retriever.SetupStep.NEEDS_SPACE
import app.retriever.SetupStep.NONE
import app.retriever.SetupStep.SCHEDULE
import app.retriever.SetupStep.SYNC_NAMES
import app.retriever.SetupStep.TUNE
import app.retriever.SetupStep.VERIFY
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.retriever.core.Backend
import app.retriever.core.CoreDispatchers
import app.retriever.core.DeviceProfile
import app.retriever.core.ModelVariant
import app.retriever.core.Retriever
import app.retriever.core.Tier
import app.retriever.data.AccessService
import app.retriever.data.IndexService
import app.retriever.data.ModelRepository
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

    @Test fun startedWithoutFilesAsksForThem() = assertEquals(ASK_FILES, next(SetupUi(started = true)))

    @Test fun filesGrantedFirstTimeSyncsNames() = assertEquals(SYNC_NAMES, next(SetupUi(started = true, access = Access.ALL)))

    @Test fun noSpace() = assertEquals(NEEDS_SPACE, next(going, hasSpace = false))

    @Test fun unmeteredDownloadsStraightAway() = assertEquals(DOWNLOAD, next(going))

    @Test fun meteredUnansweredAsks() = assertEquals(ASK_MOBILE_DATA, next(going, metered = true))

    @Test fun meteredWaitForWifiStillQueuesTheDownload() =
        assertEquals(DOWNLOAD, next(going.copy(allowMetered = false), metered = true))

    @Test fun stoppedDownloadWaitsForTheUser() = assertEquals(NONE, next(going.copy(downloadStopped = true)))

    @Test fun downloadingDoesNothing() =
        assertEquals(NONE, next(going.copy(model = ModelState.Downloading(0.4f, false))))

    @Test fun fileInPlaceIsChecked() = assertEquals(VERIFY, next(going.copy(model = ModelState.Checking)))

    @Test fun failedDownloadWaitsForTryAgain() =
        assertEquals(NONE, next(going.copy(model = ModelState.Failed("x"))))

    @Test fun downloadDoneNoProfileTunes() = assertEquals(TUNE, next(going.copy(model = ModelState.Done)))

    @Test fun tuneFailedDoesNotLoop() =
        assertEquals(NONE, next(going.copy(model = ModelState.Done, tune = TuneState.Failed("x"))))

    @Test fun tuneRunningDoesNotStartAnother() =
        assertEquals(NONE, next(going.copy(model = ModelState.Done, tune = TuneState.Running)))

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
