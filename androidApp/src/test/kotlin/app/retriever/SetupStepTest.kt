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
import org.junit.Assert.assertEquals
import org.junit.Test

class SetupStepTest {
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
