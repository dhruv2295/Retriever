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

    @Test fun welcomeAsksAccessThenPlanDemoChooseDone() {
        assertEquals(SetupStage.WELCOME to SetupStep.NONE, at(SetupMark.NONE, access = false).let { it.stage to it.step })
        // Access granted is not enough: the person confirms it on the welcome, and nothing runs before.
        assertEquals(SetupStage.WELCOME to SetupStep.NONE, at(SetupMark.NONE).let { it.stage to it.step })
        assertTrue(at(SetupMark.NONE).showSetup)
        assertEquals(SetupStage.SAMPLE to SetupStep.PLAN, at(SetupMark.ACCESS_CHOSEN).let { it.stage to it.step })
        assertEquals(SetupStage.SAMPLE to SetupStep.READ_SAMPLE, at(SetupMark.ACCESS_CHOSEN, planned = 30).let { it.stage to it.step })
        assertEquals(SetupStage.CHOOSE to SetupStep.READ_SAMPLE, at(SetupMark.CHOOSING, planned = 30).let { it.stage to it.step })
        assertEquals(SetupStage.DONE to SetupStep.SCHEDULE, at(SetupMark.CHOOSING, decided = true, planned = 30).let { it.stage to it.step })
        assertTrue(at(SetupMark.ACCESS_CHOSEN, planned = 30).showSetup)
        assertTrue(at(SetupMark.CHOOSING, planned = 30).showSetup, "setup shows until a choice is made")
        assertFalse(at(SetupMark.CHOOSING, decided = true).showSetup)
    }

    @Test fun backFromTheChoiceReturnsToTheDemoOrAfterASkipToTheWelcome() {
        assertEquals(SetupMark.ACCESS_CHOSEN, SetupPolicy.back(SetupStage.CHOOSE, skipped = false))
        assertEquals(SetupStage.SAMPLE, at(SetupPolicy.back(SetupStage.CHOOSE, skipped = false)!!, planned = 30).stage)
        assertEquals(SetupMark.NONE, SetupPolicy.back(SetupStage.CHOOSE, skipped = true))
        assertEquals(SetupStage.WELCOME, at(SetupPolicy.back(SetupStage.CHOOSE, skipped = true)!!, planned = 30).stage)
        for (stage in listOf(SetupStage.WELCOME, SetupStage.SAMPLE, SetupStage.DONE)) {
            assertNull(SetupPolicy.back(stage, skipped = false), "$stage has no setup screen behind it")
        }
    }

    /** The Try screen is gone: a mark saved on it resumes the demo, never the welcome (an unknown mark's NONE). */
    @Test fun aSavedTryingResumesTheDemo() {
        assertEquals(SetupMark.ACCESS_CHOSEN, SetupMark.saved("TRYING"))
        assertEquals(SetupStage.SAMPLE, at(SetupMark.saved("TRYING")!!, planned = 30).stage)
        assertEquals(SetupMark.CHOOSING, SetupMark.saved("CHOOSING"))
        assertNull(SetupMark.saved("STARTED"))
        assertNull(SetupMark.saved(null))
    }

    @Test fun afterTheDemoChoosesOnlyWhenFilesWaitBeyondIt() {
        assertEquals(SetupMark.CHOOSING, SetupPolicy.afterDemo(nothingLeft = false))
        assertNull(SetupPolicy.afterDemo(nothingLeft = true), "nothing to choose: process everything")
    }

    @Test fun anEmptyLibraryNeedsNoChoice() {
        // planOrCommit found nothing to plan and read everything: decided with no plan.
        assertEquals(SetupStage.DONE to SetupStep.SCHEDULE, at(SetupMark.ACCESS_CHOSEN, decided = true).let { it.stage to it.step })
    }

    @Test fun skipTheDemoLeadsToTheChoiceButNeverSkipsAccess() {
        val skipped = at(SetupMark.ACCESS_CHOSEN, skipped = true, planned = 30)
        assertEquals(SetupStage.CHOOSE to SetupStep.READ_SAMPLE, skipped.stage to skipped.step)
        assertTrue(skipped.showSetup, "a skip leads to the choice, not past it")
        assertEquals(SetupStage.CHOOSE to SetupStep.PLAN, at(SetupMark.ACCESS_CHOSEN, skipped = true).let { it.stage to it.step })
        assertEquals(SetupStage.DONE, at(SetupMark.ACCESS_CHOSEN, skipped = true, decided = true).stage)

        // A skip before access: setup opens on the welcome until access is chosen, and nothing runs.
        val noAccess = at(SetupMark.NONE, access = false, skipped = true)
        assertEquals(SetupStage.WELCOME to SetupStep.NONE, noAccess.stage to noAccess.step)
        assertTrue(noAccess.showSetup)
        assertFalse(noAccess.libraryChosen)
    }

    /** One rule for "the demo is still running": the reading bypass, the notification ask and its demo line all use it. */
    @Test fun theDemoRunsUntilStoppedSkippedOrDecided() {
        assertTrue(at(SetupMark.ACCESS_CHOSEN, planned = 30).demoRunning)
        assertFalse(at(SetupMark.CHOOSING, planned = 30).demoRunning, "its Continue stops it")
        assertFalse(at(SetupMark.ACCESS_CHOSEN, skipped = true, planned = 30).demoRunning)
        assertFalse(at(SetupMark.ACCESS_CHOSEN, decided = true, planned = 30).demoRunning)
    }

    @Test fun losingAccessReturnsToTheWelcomeAndStopsTheSteps() {
        val revoked = at(SetupMark.CHOOSING, access = false, planned = 30)
        assertEquals(SetupStage.WELCOME to SetupStep.NONE, revoked.stage to revoked.step)
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

        val searched = Prefs(searchTried = true)
        assertFalse(UpgradePolicy.showOnHome(UpgradeStage.OFFER, Prefs()), "only after the person has searched")
        assertTrue(UpgradePolicy.showOnHome(UpgradeStage.OFFER, searched))
        assertFalse(UpgradePolicy.showOnHome(UpgradeStage.OFFER, searched.copy(upgradeOfferDismissed = true)))
        assertTrue(UpgradePolicy.showOnHome(UpgradeStage.DOWNLOAD, Prefs()))
        assertTrue(UpgradePolicy.showOnHome(UpgradeStage.TUNE, Prefs()))
        assertFalse(UpgradePolicy.showOnHome(UpgradeStage.MEANING, searched), "the reading status says it")
        assertFalse(UpgradePolicy.showOnHome(UpgradeStage.UNAVAILABLE, searched))
    }

    @Test fun newSentences() {
        val nb = Words.NBSP
        assertEquals("12 files · 640${nb}MB", Words.countAndSize(FileTotals(12, 640, 0), "640 MB"))
        assertEquals("12 files · At least 1.8${nb}GB", Words.countAndSize(FileTotals(12, 1800, 3), "1.8 GB"))
        assertEquals("8 files · Size unavailable", Words.countAndSize(FileTotals(8, 0, 8), "Zero KB"))
        assertEquals("3 of 7 photos processed", Words.kindRead(3, 7, Kind.PHOTO))
        assertEquals("1 of 2 PDFs processed", Words.kindRead(1, 2, Kind.PDF))
        assertEquals("Adding search by meaning · 4 of 10 files", Words.addingMeaning(ReadingProgress(read = 9, partlyRead = 1, meaningWaiting = 6)))
        fun demo(pending: Int) = TasteProgress(emptyMap(), emptySet(), 3, null, pending, 30)
        val plain = "One small notice at the top of your screen while it works, so you can see how far it’s got or pause it. No sounds, no other alerts."
        assertEquals("$plain We can also let you know when the demo is done.", Words.notificationsAsk(demoRunning = true, demo(pending = 10)))
        assertEquals(plain, Words.notificationsAsk(demoRunning = false, demo(pending = 10)), "stopped, skipped or decided")
        assertEquals(plain, Words.notificationsAsk(demoRunning = true, demo(pending = 0)))
        assertEquals("12 of 40 files processed", Words.readOf(12, 40))
        assertEquals("Retriever looks inside the files on this phone so you can find them by what’s in them.", Words.wholeDeviceNote(android))
        assertEquals(
            "iOS lets Retriever see your photos and the folders you pick in Files.",
            Words.iosAccessNote(DeviceWords.ios("iPad")),
        )
        assertEquals("Download for meaning search", Words.upgradeHint(2, UpgradeStage.OFFER))
        assertNull(Words.upgradeHint(3, UpgradeStage.OFFER))
        assertNull(Words.upgradeHint(0, UpgradeStage.UNAVAILABLE))
        assertEquals("Setting up processing for this iPhone", Words.tuneTitle(DeviceWords.ios("iPhone")))
        assertEquals("Couldn’t finish setting up processing for this phone.", Words.tuneFailed(android))
        assertEquals("Documents and PDFs", Words.documentsTitle(2, 3))
        assertEquals("PDFs", Words.documentsTitle(2, 0), "PDFs alone are never titled Documents")
        assertEquals("Documents", Words.documentsTitle(0, 3))
        assertTrue(Words.wordsOnly(textOnly = true, modelMissing = false, setUp = true), "a phone too small for the model")
        assertTrue(Words.wordsOnly(textOnly = false, modelMissing = true, setUp = false))
        assertTrue(Words.wordsOnly(textOnly = false, modelMissing = false, setUp = false), "downloaded, not tuned yet")
        assertFalse(Words.wordsOnly(textOnly = false, modelMissing = false, setUp = true))
    }

    /** The demo's muted line: a measured time once there is one, else "About a minute."; search works from the first frame. */
    @Test fun demoNote() {
        assertEquals("About a minute.", Words.demoNote(null))
        assertEquals("Less than a minute left.", Words.demoNote(59))
        assertEquals("About a minute left.", Words.demoNote(60))
        assertEquals("About a minute left.", Words.demoNote(89))
        assertEquals("About 2 minutes left.", Words.demoNote(90))
        assertEquals("About 3 minutes left.", Words.demoNote(170))
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
