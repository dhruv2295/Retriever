package app.retriever.setup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.retriever.ShotTheme
import app.retriever.access.Access
import app.retriever.access.Photos
import app.retriever.access.PickedFolder
import app.retriever.core.Backend
import app.retriever.core.FileState
import app.retriever.core.IndexState
import app.retriever.core.Kind
import app.retriever.core.Prefs
import app.retriever.core.SearchEngine
import app.retriever.core.SetupInputs
import app.retriever.core.SetupMark
import app.retriever.core.SourceFile
import app.retriever.core.StopReason
import app.retriever.core.Store
import app.retriever.core.TasteProgress
import app.retriever.core.UpgradeStage
import app.retriever.core.Words
import app.retriever.home.HomeUi
import app.retriever.model.UpgradeUi
import app.retriever.search.SearchActions
import app.retriever.search.SearchPane
import app.retriever.search.SearchUi
import app.retriever.search.answered
import app.retriever.shot
import app.retriever.status.StatusActions
import app.retriever.status.StatusContent
import app.retriever.tallWindowForBigText
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders Setup and Status states to androidApp/build/shots for a visual check without a device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SetupStatusShotsTest {
    @get:Rule val rule = createComposeRule()

    /** [tall] fits a whole Status page, so the sections below the fold are in the shot too. */
    private fun shot(name: String, dark: Boolean = false, fontScale: Float = 1f, tall: Boolean = false, settleMs: Long = 0, content: @Composable () -> Unit) {
        tallWindowForBigText(fontScale)
        if (tall) RuntimeEnvironment.setQualifiers("+h1500dp")
        rule.setContent { ShotTheme(dark, fontScale, content) }
        if (settleMs > 0) rule.mainClock.advanceTimeBy(settleMs)
        rule.shot(name)
    }

    private fun setup(
        name: String,
        ui: SetupUi,
        dark: Boolean = false,
        fontScale: Float = 1f,
        actions: SetupActions = SetupActions(),
        filesDenied: Boolean = false,
    ) = shot(name, dark, fontScale, settleMs = if (ui.stage == app.retriever.core.SetupStage.WELCOME) 4_000 else 0) {
        val read = ui.taste?.read ?: 0
        val index = IndexState(running = read < 71, currentFile = previewFiles.first().takeIf { read < 71 }, recentFiles = previewFiles.drop(1).take(read))
        SetupContent(ui, filesDenied = filesDenied, actions = actions, index = { index }, reduceMotion = true)
    }

    /** Light, dark and 1.5x text of one first-run state. */
    private fun three(name: String, ui: SetupUi, variant: Int) = when (variant) {
        0 -> setup("${name}_light", ui)
        1 -> setup("${name}_dark", ui, dark = true)
        else -> setup("${name}_text1_5", ui, fontScale = 1.5f)
    }

    private val welcome = SetupUi()

    @Test fun welcomeLight() {
        three("setup_welcome", welcome, 0)
        rule.onNodeWithText(Words.WELCOME_TITLE).assertExists()
        rule.onNodeWithText(Words.GET_STARTED).assertExists()
        rule.onNodeWithText(Words.SEARCH_BY_NAME).assertExists()
        // No download on the way in.
        rule.onAllNodesWithText("MB", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("download", substring = true, ignoreCase = true).assertCountEquals(0)
    }

    @Test fun welcomeDark() = three("setup_welcome", welcome, 1)

    @Test fun welcomeBigText() = three("setup_welcome", welcome, 2)

    @Test fun welcomeButtonsCallTheirActions() {
        val calls = mutableListOf<String>()
        setup("setup_welcome_buttons", welcome, actions = SetupActions(getStarted = { calls += "start" }, searchByName = { calls += "names" }))
        rule.onNodeWithText(Words.GET_STARTED).performClick()
        rule.onNodeWithText(Words.SEARCH_BY_NAME).performClick()
        assertEquals(listOf("start", "names"), calls)
    }

    private val access = SetupUi(setup = SetupInputs(SetupMark.STARTED))
    private val accessGiven = SetupUi(setup = SetupInputs(SetupMark.STARTED, accessGiven = true), access = Access(false, Photos.ALL, videos = true))

    @Test fun accessWaitsForAChoice() {
        setup("setup_access_light", access)
        rule.onNodeWithText(Words.CHOOSE_ACCESS).assertExists()
        rule.onNodeWithText(Words.FILES_AND_FOLDERS).assertExists()
        rule.onNodeWithText(Words.PHOTOS_AND_VIDEOS).assertExists()
        rule.onNodeWithText(Words.ADD_FOLDER).assertExists()
        rule.onNodeWithText(Words.CONTINUE).assertIsNotEnabled()
    }

    @Test fun accessDark() = setup("setup_access_dark", accessGiven, dark = true)

    @Test fun accessBigText() = setup("setup_access_text1_5", accessGiven, fontScale = 1.5f)

    @Test fun accessDeniedSaysWhatToDo() {
        setup("setup_access_denied_light", access, filesDenied = true)
        rule.onNodeWithText(Words.NOTHING_SELECTED).assertExists()
    }

    @Test fun accessGivenContinues() {
        var continued = false
        setup("setup_access_given_light", accessGiven, actions = SetupActions(continueFromAccess = { continued = true }))
        rule.onNodeWithText(Words.CONTINUE).assertIsEnabled().performClick()
        assertEquals(true, continued)
    }

    /** [read] of a 71-file plan. */
    private fun progress(read: Int): TasteProgress {
        val plan = linkedMapOf(Kind.SCREENSHOT to 15, Kind.DOC to 12, Kind.PDF to 10, Kind.PHOTO to 32, Kind.VIDEO to 2)
        var left = read
        val byKind = plan.mapValues { (_, n) -> minOf(left, n).also { left -= it } to n }
        return TasteProgress(byKind, byKind.filterValues { it.first == it.second }.keys, read, if (read >= 71) 0 else 75, read >= 20)
    }
    private val chosen = SetupInputs(SetupMark.ACCESS_CHOSEN, planned = 71, accessGiven = true)
    private val readingFew = SetupUi(setup = chosen, access = Access.ALL, taste = progress(4))
    private val readFew = readingFew.copy(taste = progress(71))

    @Test fun sampleReadingLight() {
        three("setup_sample_reading", readingFew, 0)
        rule.onAllNodesWithText(Words.TRY_SEARCH).assertCountEquals(0)
        rule.onNodeWithText(Words.GO_TO_HOME).assertIsDisplayed()
        rule.onNodeWithText(Words.READING_NOW).assertExists()
        rule.onAllNodesWithText("/", substring = true).assertCountEquals(0)
    }

    @Test fun sampleReadingDark() = three("setup_sample_reading", readingFew, 1)

    @Test fun sampleReadingBigText() = three("setup_sample_reading", readingFew, 2)

    @Test fun samplePaused() {
        setup("setup_sample_paused_light", readingFew.copy(pauseReason = "Waiting to charge"))
        rule.onNodeWithText("Waiting to charge").assertExists()
    }

    @Test fun sampleDoneLight() {
        var tried = false
        setup("setup_sample_done_light", readFew, actions = SetupActions(tryNow = { tried = true }))
        rule.onNodeWithText(Words.TRY_SEARCH).assertIsDisplayed().performClick()
        assertEquals(true, tried)
    }

    @Test fun sampleDoneDark() = three("setup_sample_done", readFew, 1)

    @Test fun sampleDoneBigText() = three("setup_sample_done", readFew, 2)

    // --- Try it and the commit choice ---

    private val tryUi = readFew.copy(setup = chosen.copy(mark = SetupMark.TRYING))
    private val scoped = SearchUi(setUp = true, scope = (1L..79L).toList(), unread = 31, photosRead = 12)
    private fun file(name: String, kind: Kind) = SourceFile("demo:$name", name, "Pictures", "image/png", kind, 120_000, 1_760_000_000_000)

    @Test fun tryWithoutSuggestionsLight() {
        shot("setup_tryit_empty_light") { TryItContent(tryUi, scoped, SearchActions(), SetupActions()) }
        rule.onNodeWithText(Words.TRY_TITLE).assertExists()
        rule.onNodeWithText(Words.searchingSample(79)).assertExists()
        rule.onNodeWithText(Words.TRY_WITHOUT_SUGGESTIONS).assertExists()
        rule.onNodeWithText(Words.CHOOSE_WHEN).assertExists()
        rule.onNodeWithText(Words.GO_TO_HOME).assertExists()
        rule.onAllNodesWithText("Settings").assertCountEquals(0)
    }

    @Test fun tryWithSuggestionsShowsThree() {
        val terms = mutableStateOf(emptyList<String>())
        rule.setContent { ShotTheme(false) { TryItContent(tryUi, scoped, SearchActions(), SetupActions(), suggestions = terms.value) } }
        rule.onNodeWithText(Words.TRY_WITHOUT_SUGGESTIONS).assertExists()
        terms.value = listOf("invoice", "deposit", "lease")
        rule.waitForIdle()
        rule.onNodeWithText("invoice").assertExists()
        rule.onAllNodesWithText(Words.TRY_WITHOUT_SUGGESTIONS).assertCountEquals(0)
        rule.shot("setup_tryit_suggestions_light")
    }

    @Test fun tryOffersTheUpgradeOnceSearched() {
        shot("setup_tryit_upgrade_light") {
            TryItContent(tryUi, scoped, SearchActions(), SetupActions(), upgrade = UpgradeUi(UpgradeStage.OFFER, sizeBytes = 387_710_976))
        }
        rule.onNodeWithText(Words.UPGRADE_TITLE).assertExists()
    }

    @Test fun tryEmptyDark() = shot("setup_tryit_empty_dark", dark = true) { TryItContent(tryUi, scoped, SearchActions(), SetupActions()) }

    @Test fun tryEmptyBigText() = shot("setup_tryit_empty_text1_5", fontScale = 1.5f) { TryItContent(tryUi, scoped, SearchActions(), SetupActions()) }

    private val answered = scoped.answered(
        "boarding pass",
        listOf(
            SearchEngine.Hit(file("Screenshot_0812.png", Kind.SCREENSHOT), 1, 0, "", null, 1.0),
            SearchEngine.Hit(file("Itinerary_March.pdf", Kind.PDF), 2, 0, "p. 2", "boarding closes 40 min before departure", 0.9),
        ),
    )

    @Test fun tryItAfterSearchLight() {
        shot("setup_tryit_results_light") { SearchPane(answered, HomeUi(), SearchActions()) }
        rule.onNodeWithText(Words.searchingSample(79)).assertExists()
        rule.onNodeWithText("Still reading 31 more").assertExists()
        rule.onAllNodesWithText("Photos").assertCountEquals(0)
    }

    @Test fun tryItAfterSearchDark() = shot("setup_tryit_results_dark", dark = true) { SearchPane(answered, HomeUi(), SearchActions()) }

    @Test fun tryItAfterSearchBigText() = shot("setup_tryit_results_text1_5", fontScale = 1.5f) { SearchPane(answered, HomeUi(), SearchActions()) }

    @Test fun tryGoesHomeWhenNothingIsLeft() {
        shot("setup_tryit_done_light") { TryItContent(tryUi.copy(moreFiles = 0), scoped, SearchActions(), SetupActions()) }
        rule.onAllNodesWithText(Words.GO_TO_HOME).assertCountEquals(2)
        rule.onAllNodesWithText(Words.CHOOSE_WHEN).assertCountEquals(0)
    }

    private val commitUi = tryUi.copy(setup = chosen.copy(mark = SetupMark.CHOOSING))

    @Test fun commitLight() {
        shot("setup_commit_light") { SetupContent(commitUi) }
        rule.onNodeWithText("Search inside more files").assertExists()
        rule.onNodeWithText("A large library can take hours of reading, spread across several sessions. You don’t need to wait.").assertExists()
    }

    @Test fun commitDark() = shot("setup_commit_dark", dark = true) { SetupContent(commitUi) }

    @Test fun commitBigText() = shot("setup_commit_text1_5", fontScale = 1.5f) { SetupContent(commitUi) }

    @Test fun threeButtonsCallTheirActions() {
        val calls = mutableListOf<String>()
        val actions = SetupActions(commit = { calls += "commit($it)" }, decideLater = { calls += "later" })
        shot("setup_commit_buttons") { SetupContent(commitUi, actions = actions) }
        rule.onNodeWithText("While charging").performClick()
        assertEquals(emptyList<String>(), calls)
        rule.onNodeWithText("Use this schedule").performClick()
        rule.onNodeWithText("Read now for 15 minutes").performClick()
        rule.onNodeWithText("Start reading").performClick()
        rule.onNodeWithText("Not now").performClick()
        rule.onNodeWithText("Go to Home").performClick()
        assertEquals(listOf("commit(true)", "commit(false)", "later"), calls)
    }

    @Test fun continueAsksForTheCommitChoice() {
        var continued = false
        shot("setup_tryit_continue") { TryItContent(tryUi, scoped, SearchActions(), SetupActions(continueToCommit = { continued = true })) }
        rule.onNodeWithText(Words.CHOOSE_WHEN).performClick()
        assertEquals(true, continued)
    }

    private val lowMemory = SetupUi(textOnly = true, ramGb = 3.0)

    @Test fun setupTextOnly() = setup("setup_text_only_light", lowMemory)

    @Test fun setupTextOnlyDark() = setup("setup_text_only_dark", lowMemory, dark = true)

    private val prefs = Prefs(excludedFolders = setOf("Android/media/WhatsApp/Stickers"))
    private val reading = app.retriever.readCounts(
        mapOf(FileState.INDEXED to 3_412L, FileState.PENDING to 8_118L, FileState.SKIPPED to 3L),
        mapOf(Kind.PHOTO to 9_000L, Kind.PDF to 2_533L),
        tailChunks = 12,
    )
    private val previewFiles = (1..8).map { i ->
        SourceFile("demo:reading-$i", "Photo $i.jpg", "Photos", "image/jpeg", Kind.PHOTO, 1_200_000, 1_760_000_000_000)
    }

    @Before fun readingThumbnails() = registerReadingThumbnails(previewFiles.map { it.uri })

    private val running = IndexState(
        running = true,
        processed = 120,
        pendingAtStart = 8_238,
        current = "IMG_20250914_183022.jpg",
        currentFile = previewFiles.first(),
        recentFiles = previewFiles,
        etaMinutes = 95,
        backend = Backend.GPU,
        detail = "the vision encoder runs on the processor",
    )
    private val skippedFiles = listOf("Scan 3.pdf" to "Download", "Old notes.docx" to "Documents", "IMG_0001.heic" to "DCIM/Camera")
        .mapIndexed { i, (name, folder) ->
            Store.StoredFile(i.toLong(), SourceFile("demo:$i", name, folder, "application/octet-stream", Kind.OTHER, 1_000, 0), 3)
        }

    private fun status(
        name: String,
        counts: Store.Counts = reading,
        index: IndexState = running,
        open: Boolean = false,
        dark: Boolean = false,
        fontScale: Float = 1f,
        access: Access = Access.ALL,
        textOnly: Boolean = false,
        tall: Boolean = false,
    ) = shot(name, dark, fontScale, tall) {
        StatusContent(
            counts, prefs, index, skippedFiles, open, batteryHint = true,
            actions = StatusActions(openSettings = {}), access = access, textOnly = textOnly,
        )
    }

    private val caughtUp = app.retriever.readCounts(mapOf(FileState.INDEXED to 11_530L), mapOf(Kind.PHOTO to 9_000L), 0)
    private val idle = IndexState(lastStop = StopReason.DONE, backend = Backend.GPU)
    private val mediaOnly = Access(false, Photos.ALL, videos = true, audio = true)
    private val withFolders = Access(
        false,
        Photos.SELECTED,
        folders = listOf(
            PickedFolder("content://com.android.externalstorage.documents/tree/primary%3ADocuments", "Documents"),
            PickedFolder("content://com.android.externalstorage.documents/tree/1A2B-3C4D%3APapers", "SD card/Papers"),
        ),
    )

    private fun coverage(name: String, access: Access, dark: Boolean) = status(name, counts = caughtUp, index = idle, dark = dark, access = access, tall = true)

    @Test fun statusCoveragePhotosOnly() = coverage("status_coverage_photos_only_light", mediaOnly, dark = false)

    @Test fun statusCoveragePhotosOnlyDark() = coverage("status_coverage_photos_only_dark", mediaOnly, dark = true)

    @Test fun statusCoverageFolders() = coverage("status_coverage_folders_light", withFolders, dark = false)

    @Test fun statusCoverageFoldersDark() = coverage("status_coverage_folders_dark", withFolders, dark = true)

    private val textOnlyCounts = app.retriever.readCounts(mapOf(FileState.INDEXED to 2_140L, FileState.PENDING to 310L), mapOf(Kind.PDF to 900L), 0)

    @Test fun statusTextOnly() = status("status_text_only_light", textOnlyCounts, idle, textOnly = true, tall = true)

    @Test fun statusTextOnlyDark() = status("status_text_only_dark", textOnlyCounts, idle, dark = true, textOnly = true, tall = true)

    private val tail = reading.copy(tailFiles = 7)

    @Test fun statusTailFiles() = status("status_tail_files_light", tail, running.copy(running = false, lastStop = StopReason.BUDGET))

    @Test fun statusTailFilesDark() = status("status_tail_files_dark", tail, running.copy(running = false, lastStop = StopReason.BUDGET), dark = true)

    @Test fun statusHoldsNoPreferenceAndLinksToSettings() {
        var opened = 0
        shot("status_links_settings_light", tall = true) {
            StatusContent(caughtUp, prefs, idle, skippedFiles, false, batteryHint = false, actions = StatusActions(openSettings = { opened++ }))
        }
        listOf("Only read while charging", "Screenshots", "PDFs", "Tune again", "Folders Retriever skips", "Read inside", "Sample mode")
            .forEach { rule.onAllNodesWithText(it, substring = true).assertCountEquals(0) }
        rule.onNodeWithText("Settings").performClick()
        assertEquals(1, opened)
    }

    @Test fun statusReading() = status("status_reading_light")

    @Test fun statusReadingDark() = status("status_reading_dark", dark = true)

    @Test fun statusReadingBigText() = status("status_reading_text1_5", fontScale = 1.5f)

    @Test fun statusSkippedOpen() = status("status_skipped_open_light", index = running.copy(running = false, lastStop = StopReason.UNPLUGGED), open = true)

    @Test fun statusCaughtUp() = status(
        "status_caught_up_dark",
        counts = app.retriever.readCounts(mapOf(FileState.INDEXED to 11_530L), mapOf(Kind.PHOTO to 9_000L), 0),
        index = IndexState(lastStop = StopReason.DONE, backend = Backend.GPU),
        dark = true,
    )
}
