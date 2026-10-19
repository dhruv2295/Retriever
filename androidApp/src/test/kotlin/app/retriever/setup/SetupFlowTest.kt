package app.retriever.setup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import app.retriever.access.Access
import app.retriever.access.AccessService
import app.retriever.core.CoreDispatchers
import app.retriever.core.Kind
import app.retriever.core.Part
import app.retriever.core.Piece
import app.retriever.core.Retriever
import app.retriever.core.SetupMark
import app.retriever.core.SetupStage
import app.retriever.core.UpgradeStage
import app.retriever.indexing.IndexService
import app.retriever.model.ModelDownload
import app.retriever.model.ModelRepository
import app.retriever.model.UpgradeFlow
import app.retriever.testFile
import app.retriever.testRetriever
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
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

/** Android's side of core's setup machine: the answers it records and the steps it runs, with no model download. */
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

    private val docs = (1..30).map { testFile("doc$it.txt", Kind.DOC) }

    /** An ordinary phone with no model: reading needs none. */
    private fun TestScope.noModel(): Retriever = testRetriever(
        docs,
        dispatchers = CoreDispatchers(StandardTestDispatcher(testScheduler)),
        extract = { listOf(Piece("", listOf(Part.Text("read content")), "read content", "read content")) },
    ).apply { uiVisible = true }

    private fun flow(r: Retriever, scope: CoroutineScope) = SetupFlow(r, AccessService(app), IndexService(app, r), scope, notificationsGranted = { false })

    @Test fun aFreshInstallWelcomesAndGetStartedSurvivesTheProcess() = runTest {
        val r = noModel()
        r.refreshSetUp()
        assertEquals(SetupStage.WELCOME, flow(r, backgroundScope).ui.value.stage)
        flow(r, backgroundScope).getStarted()
        r.setup.first { it?.mark == SetupMark.STARTED }
        // A new process reads the answer back from the store, and access is asked for even with permissions granted.
        r.refreshSetUp()
        val again = flow(r, backgroundScope)
        assertEquals(SetupStage.ACCESS, again.ui.value.stage)
        assertTrue(again.ui.value.setup.showSetup)
    }

    @Test fun continueFromAccessPlansTheSampleAndReadsItWithoutAModel() = runTest {
        val r = noModel()
        r.refreshSetUp()
        val f = flow(r, backgroundScope)
        f.getStarted()
        f.continueFromAccess()
        val sample = f.ui.first { it.stage == SetupStage.SAMPLE && it.setup.planned > 0 }
        assertFalse(sample.setup.decided)
        r.tasteProgress().first { it.readyToTry }
        f.tryNow()
        f.ui.first { it.stage == SetupStage.TRY }
        f.back()
        f.ui.first { it.stage == SetupStage.SAMPLE }
    }

    @Test fun searchByNameShowsAccessThenHomeWhileTheSampleReads() = runTest {
        val r = noModel()
        r.refreshSetUp()
        val f = flow(r, backgroundScope)
        f.searchByName()
        val access = f.ui.first { it.setup.skipped }
        assertEquals(SetupStage.ACCESS, access.stage)
        assertTrue("skipping never skips access", access.setup.showSetup)
        f.continueFromAccess()
        val home = f.ui.first { !it.setup.showSetup }
        assertEquals(SetupStage.DONE, home.stage)
        // The sample is still planned and read in the background.
        f.ui.first { it.setup.planned > 0 }
    }

    @Test fun goToHomeFromTheSampleLeavesSetupForGood() = runTest {
        val r = noModel()
        r.refreshSetUp()
        val f = flow(r, backgroundScope)
        f.getStarted()
        f.continueFromAccess()
        f.ui.first { it.stage == SetupStage.SAMPLE }
        f.skip()
        f.ui.first { !it.setup.showSetup }
        r.refreshSetUp()
        assertFalse(flow(r, backgroundScope).ui.value.setup.showSetup)
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
        assertTrue(u.ui.value.askMobileData)
        assertEquals(UpgradeStage.OFFER, u.ui.value.stage)
    }

    @Test fun aPhoneTooSmallForTheModelHasNoUpgrade() = runTest {
        val r = testRetriever(ramGb = 3.0, dispatchers = CoreDispatchers(StandardTestDispatcher(testScheduler)))
        r.refreshSetUp()
        assertEquals(UpgradeStage.UNAVAILABLE, upgrade(r, backgroundScope, metered = false).ui.first { it.progress != null }.stage)
    }
}
