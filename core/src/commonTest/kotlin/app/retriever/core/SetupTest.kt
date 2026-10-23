package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shared setup and upgrade machines, and More on your phone's totals, on each platform. */
class SetupTest {
    private val android = DeviceWords.ANDROID

    private fun at(mark: SetupMark, access: Boolean = true, skipped: Boolean = false, decided: Boolean = false, planned: Int = 0) =
        SetupInputs(mark, skipped, decided, planned).withAccess(access)

    @Test fun helloThenAccessThenTheFirstSearchThenDone() {
        // Access granted is not enough: Hello comes first, and nothing runs before the access screen's Continue.
        assertEquals(SetupStage.WELCOME to SetupStep.NONE, at(SetupMark.NONE).let { it.stage to it.step })
        assertEquals(SetupStage.ACCESS to SetupStep.NONE, at(SetupMark.GREETED, access = false).let { it.stage to it.step })
        assertEquals(SetupStage.ACCESS to SetupStep.NONE, at(SetupMark.GREETED).let { it.stage to it.step }, "Continue confirms it")
        assertTrue(at(SetupMark.GREETED).showSetup)
        assertEquals(SetupStage.SAMPLE to SetupStep.PLAN, at(SetupMark.ACCESS_CHOSEN).let { it.stage to it.step })
        assertEquals(SetupStage.SAMPLE to SetupStep.READ_SAMPLE, at(SetupMark.ACCESS_CHOSEN, planned = 30).let { it.stage to it.step })
        assertTrue(at(SetupMark.ACCESS_CHOSEN, planned = 30).showSetup)
        // The first search's Continue (finishSetup) decides: there is no choice screen.
        assertEquals(SetupStage.DONE to SetupStep.SCHEDULE, at(SetupMark.ACCESS_CHOSEN, decided = true, planned = 30).let { it.stage to it.step })
        assertFalse(at(SetupMark.ACCESS_CHOSEN, decided = true).showSetup)
    }

    @Test fun backFromAccessReturnsToHelloAndNowhereElse() {
        assertEquals(SetupMark.NONE, SetupPolicy.back(SetupStage.ACCESS))
        assertEquals(SetupStage.WELCOME, at(SetupPolicy.back(SetupStage.ACCESS)!!).stage)
        for (stage in listOf(SetupStage.WELCOME, SetupStage.SAMPLE, SetupStage.DONE)) {
            assertNull(SetupPolicy.back(stage), "$stage has no setup screen behind it")
        }
    }

    /** Screens that are gone resume where their work went: the Try screen and the old choice both reopen the first search. */
    @Test fun savedMarksOfGoneScreensResumeTheFirstSearch() {
        for (gone in listOf("TRYING", "CHOOSING")) {
            assertEquals(SetupMark.ACCESS_CHOSEN, SetupMark.saved(gone))
            assertEquals(SetupStage.SAMPLE, at(SetupMark.saved(gone)!!, planned = 30).stage)
        }
        assertNull(SetupMark.saved("STARTED"))
        assertNull(SetupMark.saved(null))
    }

    @Test fun getStartedDownloadsOnlyWhereTheModelRunsAndIsMissing() {
        assertTrue(SetupPolicy.downloadOnGetStarted(textOnly = false, modelPresent = false))
        assertFalse(SetupPolicy.downloadOnGetStarted(textOnly = false, modelPresent = true))
        assertFalse(SetupPolicy.downloadOnGetStarted(textOnly = true, modelPresent = false), "a phone too small for it")
    }

    @Test fun anEmptyLibraryEndsSetupAtOnce() {
        // planOrCommit found nothing to plan and read everything: decided with no plan.
        assertEquals(SetupStage.DONE to SetupStep.SCHEDULE, at(SetupMark.ACCESS_CHOSEN, decided = true).let { it.stage to it.step })
    }

    /** An older version's Skip the demo, saved mid-setup, still stops the demo but never skips access. */
    @Test fun anOldSkipNeverSkipsAccess() {
        val noAccess = at(SetupMark.GREETED, access = false, skipped = true)
        assertEquals(SetupStage.ACCESS to SetupStep.NONE, noAccess.stage to noAccess.step)
        assertFalse(noAccess.libraryChosen)
        assertEquals(SetupStage.SAMPLE, at(SetupMark.ACCESS_CHOSEN, skipped = true, planned = 30).stage)
    }

    /** One rule for "the demo is still running": the reading bypass, the notification ask and its demo line all use it. */
    @Test fun theDemoRunsUntilSetupFinishes() {
        assertTrue(at(SetupMark.ACCESS_CHOSEN, planned = 30).demoRunning)
        assertFalse(at(SetupMark.ACCESS_CHOSEN, skipped = true, planned = 30).demoRunning)
        assertFalse(at(SetupMark.ACCESS_CHOSEN, decided = true, planned = 30).demoRunning)
    }

    @Test fun losingAccessReturnsToTheAccessScreenAndStopsTheSteps() {
        val revoked = at(SetupMark.ACCESS_CHOSEN, access = false, planned = 30)
        assertEquals(SetupStage.ACCESS to SetupStep.NONE, revoked.stage to revoked.step)
        val status = ReadingPolicy.line(ReadingStatus(ReadingPhase.CAUGHT_UP, ""), revoked.libraryChosen, hasAccess = true, 0, DeviceWords.ANDROID)
        assertEquals(ReadingPhase.SETTING_UP to StatusAction.CONTINUE, status?.phase to status?.action)
    }

    @Test fun theUpgradeOffersThenDownloadsTunesAndAddsMeaning() {
        fun stage(present: Boolean, downloading: Boolean = false, setUp: Boolean = false, waiting: Long = 0, textOnly: Boolean = false) =
            UpgradePolicy.stage(textOnly, present, downloading, setUp, waiting)
        assertEquals(UpgradeStage.UNAVAILABLE, stage(false, textOnly = true))
        assertEquals(UpgradeStage.OFFER, stage(false))
        assertEquals(UpgradeStage.DOWNLOAD, stage(false, downloading = true))
        assertEquals(UpgradeStage.TUNE, stage(true))
        assertEquals(UpgradeStage.MEANING, stage(true, setUp = true, waiting = 5))
        assertEquals(UpgradeStage.DONE, stage(true, setUp = true))
        // Freed space or a new build: offered again, never fetched unasked.
        assertEquals(UpgradeStage.OFFER, stage(false, setUp = true))

        assertTrue(UpgradePolicy.showOnHome(UpgradeStage.OFFER, Prefs()), "an install without the model is offered it at once")
        assertFalse(UpgradePolicy.showOnHome(UpgradeStage.OFFER, Prefs(upgradeOfferDismissed = true)))
        assertTrue(UpgradePolicy.showOnHome(UpgradeStage.DOWNLOAD, Prefs()))
        assertTrue(UpgradePolicy.showOnHome(UpgradeStage.TUNE, Prefs()))
        assertFalse(UpgradePolicy.showOnHome(UpgradeStage.MEANING, Prefs()), "the reading status says it")
        assertFalse(UpgradePolicy.showOnHome(UpgradeStage.UNAVAILABLE, Prefs()))
        val card = Prefs(homeIntroDue = true)
        for (s in UpgradeStage.entries) assertFalse(UpgradePolicy.showOnHome(s, card), "$s: the one-time card says it")
    }

    @Test fun oneDescriptionStateForSetupAndHome() {
        fun d(stage: UpgradeStage, phase: DownloadPhase = DownloadPhase.NONE) = UpgradePolicy.description(stage, phase)
        assertEquals(DescriptionState.UNAVAILABLE, d(UpgradeStage.UNAVAILABLE))
        assertEquals(DescriptionState.NOT_STARTED, d(UpgradeStage.OFFER))
        assertEquals(DescriptionState.DOWNLOADING, d(UpgradeStage.DOWNLOAD))
        assertEquals(DescriptionState.DOWNLOADING, d(UpgradeStage.DOWNLOAD, DownloadPhase.RUNNING))
        assertEquals(DescriptionState.WAITING_FOR_WIFI, d(UpgradeStage.DOWNLOAD, DownloadPhase.WAITING_FOR_WIFI))
        assertEquals(DescriptionState.PREPARING, d(UpgradeStage.DOWNLOAD, DownloadPhase.CHECKING))
        assertEquals(DescriptionState.FAILED, d(UpgradeStage.DOWNLOAD, DownloadPhase.FAILED))
        assertEquals(DescriptionState.NO_SPACE, d(UpgradeStage.OFFER, DownloadPhase.NO_SPACE), "refused before it queued")
        assertEquals(DescriptionState.PREPARING, d(UpgradeStage.TUNE))
        assertEquals(DescriptionState.READY, d(UpgradeStage.MEANING))
        assertEquals(DescriptionState.READY, d(UpgradeStage.DONE))
    }

    @Test fun newSentences() {
        assertEquals("3 of 7 photos processed", Words.kindRead(3, 7, Kind.PHOTO))
        assertEquals("1 of 2 PDFs processed", Words.kindRead(1, 2, Kind.PDF))
        assertEquals("Adding search by description · 4 of 10 files", Words.addingMeaning(ReadingProgress(read = 9, partlyRead = 1, meaningWaiting = 6)))
        fun demo(pending: Int) = TasteProgress(emptyMap(), emptySet(), 3, pending, 30)
        val plain = "One small notice at the top of your screen while it works, so you can see how far it’s got or pause it. No sounds, no other alerts."
        assertEquals("$plain We can also let you know when the demo is done.", Words.notificationsAsk(demoRunning = true, demo(pending = 10)))
        assertEquals(plain, Words.notificationsAsk(demoRunning = false, demo(pending = 10)), "stopped, skipped or decided")
        assertEquals(plain, Words.notificationsAsk(demoRunning = true, demo(pending = 0)))
        assertEquals("12 of 40 files processed", Words.readOf(12, 40))
        assertEquals("Retriever looks inside the files on this phone so you can find them by what’s in them.", Words.wholeDeviceNote(android))
        assertEquals("Download for search by description", Words.upgradeHint(2, UpgradeStage.OFFER))
        assertNull(Words.upgradeHint(3, UpgradeStage.OFFER))
        assertNull(Words.upgradeHint(0, UpgradeStage.UNAVAILABLE))
        assertEquals("Setting up processing for this iPhone", Words.tuneTitle(DeviceWords.ios("iPhone")))
        assertEquals("Couldn’t finish setting up processing for this phone.", Words.tuneFailed(android))
        assertTrue(Words.wordsOnly(textOnly = true, modelMissing = false, setUp = true), "a phone too small for the model")
        assertTrue(Words.wordsOnly(textOnly = false, modelMissing = true, setUp = false))
        assertTrue(Words.wordsOnly(textOnly = false, modelMissing = false, setUp = false), "downloaded, not tuned yet")
        assertFalse(Words.wordsOnly(textOnly = false, modelMissing = false, setUp = true))
    }

    @Test fun totalsCountEveryAccessibleFileWithUnknownSizesApart() {
        val store = Store(testDriver())
        fun file(uri: String, kind: Kind, folder: String, size: Long) = SourceFile(uri, uri.substringAfterLast('/'), folder, "", kind, size, 1)
        store.upsertFiles(
            listOf(
                file("ph://1", Kind.PHOTO, "Recents", 0),
                file("ph://2", Kind.PHOTO, "Recents", 0),
                file("folder://d/a.pdf", Kind.PDF, "Docs", 100),
                file("folder://d/b.pdf", Kind.PDF, "Docs", 50),
                file("folder://d/c.bin", Kind.OTHER, "Docs", 0),
                file("folder://d/sub/d.txt", Kind.DOC, "Docs/sub", 7),
            ),
        )
        assertEquals(
            mapOf(
                Kind.PHOTO to FileTotals(2, 0, 2),
                Kind.PDF to FileTotals(2, 150, 0),
                Kind.OTHER to FileTotals(1, 0, 1),
                Kind.DOC to FileTotals(1, 7, 0),
            ),
            store.kindTotals(),
        )
        assertEquals(
            listOf(FolderTotals("Docs", FileTotals(3, 150, 1)), FolderTotals("Recents", FileTotals(2, 0, 2)), FolderTotals("Docs/sub", FileTotals(1, 7, 0))),
            store.folderTotals(),
            "a folder counts its own files, never its subfolders'",
        )
        store.removeFiles(listOf("ph://1"))
        assertEquals(FileTotals(1, 0, 1), store.kindTotals()[Kind.PHOTO], "a file no longer accessible leaves the totals")
    }
}
