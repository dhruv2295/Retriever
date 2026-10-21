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

    @Test fun welcomeAsksAccessThenPlanSampleTryChooseDone() {
        assertEquals(SetupStage.WELCOME to SetupStep.NONE, at(SetupMark.NONE, access = false).let { it.stage to it.step })
        // Access granted is not enough: the person confirms it on the welcome, and nothing runs before.
        assertEquals(SetupStage.WELCOME to SetupStep.NONE, at(SetupMark.NONE).let { it.stage to it.step })
        assertTrue(at(SetupMark.NONE).showSetup)
        assertEquals(SetupStage.SAMPLE to SetupStep.PLAN, at(SetupMark.ACCESS_CHOSEN).let { it.stage to it.step })
        assertEquals(SetupStage.SAMPLE to SetupStep.READ_SAMPLE, at(SetupMark.ACCESS_CHOSEN, planned = 30).let { it.stage to it.step })
        assertEquals(SetupStage.TRY to SetupStep.READ_SAMPLE, at(SetupMark.TRYING, planned = 30).let { it.stage to it.step })
        assertEquals(SetupStage.CHOOSE to SetupStep.READ_SAMPLE, at(SetupMark.CHOOSING, planned = 30).let { it.stage to it.step })
        assertEquals(SetupStage.DONE to SetupStep.SCHEDULE, at(SetupMark.CHOOSING, decided = true, planned = 30).let { it.stage to it.step })
        assertTrue(at(SetupMark.ACCESS_CHOSEN, planned = 30).showSetup)
        assertFalse(at(SetupMark.CHOOSING, decided = true).showSetup)
    }

    @Test fun backLeavesTryForTheSampleAndTheChoiceForTry() {
        assertEquals(SetupMark.ACCESS_CHOSEN, SetupPolicy.back(SetupStage.TRY))
        assertEquals(SetupMark.TRYING, SetupPolicy.back(SetupStage.CHOOSE))
        for (stage in listOf(SetupStage.WELCOME, SetupStage.SAMPLE, SetupStage.DONE)) {
            assertNull(SetupPolicy.back(stage), "$stage has no setup screen behind it")
        }
        // Each mark Back sets lands on the screen before.
        assertEquals(SetupStage.SAMPLE, at(SetupPolicy.back(SetupStage.TRY)!!, planned = 30).stage)
        assertEquals(SetupStage.TRY, at(SetupPolicy.back(SetupStage.CHOOSE)!!, planned = 30).stage)
    }

    @Test fun afterTryChoosesOnlyWhenFilesWaitBeyondTheSample() {
        assertEquals(SetupMark.CHOOSING, SetupPolicy.afterTry(nothingLeft = false))
        assertNull(SetupPolicy.afterTry(nothingLeft = true), "nothing to choose: read everything")
    }

    @Test fun anEmptyLibraryNeedsNoChoice() {
        // planOrCommit found nothing to plan and read everything: decided with no plan.
        assertEquals(SetupStage.DONE to SetupStep.SCHEDULE, at(SetupMark.ACCESS_CHOSEN, decided = true).let { it.stage to it.step })
    }

    @Test fun goToHomeKeepsTheSampleReadingButNeverSkipsAccess() {
        val left = at(SetupMark.ACCESS_CHOSEN, skipped = true, planned = 30)
        assertEquals(SetupStage.DONE to SetupStep.READ_SAMPLE, left.stage to left.step)
        assertFalse(left.showSetup)
        assertEquals(SetupStep.PLAN, at(SetupMark.TRYING, skipped = true).step, "a plan interrupted by the process is made on return")

        // Search by name before access: setup opens on the welcome until access is chosen, and nothing runs.
        val noAccess = at(SetupMark.NONE, access = false, skipped = true)
        assertEquals(SetupStage.WELCOME to SetupStep.NONE, noAccess.stage to noAccess.step)
        assertTrue(noAccess.showSetup)
        assertFalse(noAccess.libraryChosen)
        assertEquals(SetupStage.DONE to SetupStep.PLAN, at(SetupMark.ACCESS_CHOSEN, skipped = true).let { it.stage to it.step })
    }

    @Test fun losingAccessReturnsToTheWelcomeAndStopsTheSteps() {
        val revoked = at(SetupMark.TRYING, access = false, planned = 30)
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
        assertEquals("Searching every file’s name, and inside the 1 file processed so far.", Words.searchingTry(1))
        assertEquals("Searching every file’s name, and inside the 124 files processed so far.", Words.searchingTry(124))
        assertEquals("Find a file you know", Words.TRY_TITLE)
        assertEquals("Type something you remember from it, like a name, a place or an amount, or tap a suggestion.", Words.TRY_BODY)
        assertEquals("Open one of these files, then type something you remember from it.", Words.TRY_WITHOUT_SUGGESTIONS)
        assertEquals("Files you can try", Words.FILES_TO_TRY)
        assertEquals("Adding search by meaning · 4 of 10 files", Words.addingMeaning(ReadingProgress(read = 9, partlyRead = 1, meaningWaiting = 6)))
        assertEquals("Try it on your own files", Words.SAMPLE_TITLE)
        assertEquals(
            "Trying a few of your recent files to show you how it works. About a minute. Nothing is changed or sent.",
            Words.SAMPLE_PICKING,
        )
        fun sample(readyToTry: Boolean, pending: Int) = TasteProgress(emptyMap(), emptySet(), if (readyToTry) 20 else 3, null, readyToTry, pending)
        val plain = "One small notice at the top of your screen while it works, so you can see how far it’s got or pause it. No sounds, no other alerts."
        assertEquals(
            "$plain We can also let you know when your sample is ready.",
            Words.notificationsAsk(decided = false, sample(readyToTry = false, pending = 10)),
        )
        assertEquals(plain, Words.notificationsAsk(decided = false, sample(readyToTry = true, pending = 10)), "ready: its message is already claimed")
        assertEquals(plain, Words.notificationsAsk(decided = true, sample(readyToTry = false, pending = 10)))
        assertEquals(plain, Words.notificationsAsk(decided = false, sample(readyToTry = false, pending = 0)))
        assertEquals("12 of 40 files processed", Words.readOf(12, 40))
        assertEquals("Retriever processes the files on this phone to find them by what’s inside.", Words.wholeDeviceNote(android))
        assertEquals(
            "iOS lets Retriever read your photos and the folders you pick in Files.",
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

    /** The sample's muted line: a measured time once there is one, then whether search can be tried yet. */
    @Test fun sampleNote() {
        assertNull(Words.sampleNote(reading = false, readyToTry = true, secondsLeft = 90), "nothing while paused or done")
        assertEquals("You can try search once a few files are processed.", Words.sampleNote(true, false, null))
        assertEquals("Ready to try. Search for a word you remember from a recent file.", Words.sampleNote(true, true, null))
        assertEquals("Less than a minute left. You can try search once a few files are processed.", Words.sampleNote(true, false, 59))
        assertEquals("About a minute left. Ready to try.", Words.sampleNote(true, true, 60))
        assertEquals("About a minute left. Ready to try.", Words.sampleNote(true, true, 89))
        assertEquals("About 2 minutes left. Ready to try.", Words.sampleNote(true, true, 90))
        assertEquals("About 3 minutes left. Ready to try.", Words.sampleNote(true, true, 170))
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
