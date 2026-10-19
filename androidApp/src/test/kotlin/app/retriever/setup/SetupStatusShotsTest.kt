package app.retriever.setup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
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
import app.retriever.core.ModelBuilds
import app.retriever.core.Prefs
import app.retriever.core.ReadEstimate
import app.retriever.core.SearchEngine
import app.retriever.core.SourceFile
import app.retriever.core.StopReason
import app.retriever.core.Store
import app.retriever.core.TasteProgress
import app.retriever.home.HomeUi
import app.retriever.model.ModelState
import app.retriever.model.TuneState
import app.retriever.model.spaceMessage
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

    private val build = ModelBuilds.npu.first().variant.generic
    private val going = SetupUi(started = true, access = Access.ALL, namesSynced = true, build = build)

    /** [tall] fits a whole Status page, so the sections below the fold are in the shot too. */
    private fun shot(name: String, dark: Boolean = false, fontScale: Float = 1f, tall: Boolean = false, settleMs: Long = 0, content: @Composable () -> Unit) {
        tallWindowForBigText(fontScale)
        if (tall) RuntimeEnvironment.setQualifiers("+h1500dp")
        rule.setContent { ShotTheme(dark, fontScale, content) }
        if (settleMs > 0) rule.mainClock.advanceTimeBy(settleMs)
        rule.shot(name)
    }

    private fun setup(name: String, ui: SetupUi, dark: Boolean = false, fontScale: Float = 1f) =
        shot(name, dark, fontScale, settleMs = if (ui.stage == SetupStage.WELCOME) 4_000 else 0) {
            SetupContent(ui, reading = { read ->
                SetupReadingFiles(if (read < 71) previewFiles.first() else null, previewFiles.take(read), null, read)
            })
        }

    /** Light, dark and 1.5x text of one first-run state. */
    private fun three(name: String, ui: SetupUi, variant: Int) = when (variant) {
        0 -> setup("${name}_light", ui)
        1 -> setup("${name}_dark", ui, dark = true)
        else -> setup("${name}_text1_5", ui, fontScale = 1.5f)
    }

    private val welcome = SetupUi(build = build)

    @Test fun welcomeLight() = three("setup_welcome", welcome, 0)

    @Test fun welcomeDark() = three("setup_welcome", welcome, 1)

    @Test fun welcomeBigText() = three("setup_welcome", welcome, 2)

    private val downloading = going.copy(model = ModelState.Downloading(0.45f, false), decided = false, taste = progress(0))

    @Test fun downloadingLight() {
        three("setup_downloading", downloading.copy(notificationAsk = true), 0)
        rule.onNodeWithText("Skip").assertExists()
        rule.onNodeWithText("Getting Retriever ready to read").assertExists()
        rule.onNodeWithText("218 MB of 485 MB").assertExists()
        rule.onNodeWithText("Stop").assertExists()
        rule.onNodeWithText("Search by name").assertExists()
    }

    @Test fun downloadingDark() = three("setup_downloading", downloading, 1)

    @Test fun downloadingBigText() = three("setup_downloading", downloading, 2)

    private val wifi = downloading.copy(model = ModelState.WaitingForWifi)

    @Test fun wifiLight() = three("setup_wifi", wifi, 0)

    @Test fun wifiDark() = three("setup_wifi", wifi, 1)

    @Test fun wifiBigText() = three("setup_wifi", wifi, 2)

    /** 14 of a 71-file plan read: no "Try it now" yet. */
    private fun progress(read: Int): TasteProgress {
        val plan = linkedMapOf(Kind.SCREENSHOT to 15, Kind.DOC to 12, Kind.PDF to 10, Kind.PHOTO to 32, Kind.VIDEO to 2)
        var left = read
        val byKind = plan.mapValues { (_, n) -> minOf(left, n).also { left -= it } to n }
        return TasteProgress(byKind, byKind.filterValues { it.first == it.second }.keys, read, if (read >= 71) 0 else 75, read >= 20)
    }
    private val readingFew = going.copy(model = ModelState.Done, ready = true, decided = false, taste = progress(14))
    private val readFew = readingFew.copy(taste = progress(71))

    @Test fun readingMidLight() {
        three("setup_reading_mid", readingFew, 0)
        rule.onAllNodesWithText("Try it now").assertCountEquals(0)
        rule.onNodeWithText("Reading now").assertExists()
        rule.onNodeWithText("Screenshots 14/15").assertExists()
    }

    @Test fun readingMidDark() = three("setup_reading_mid", readingFew, 1)

    @Test fun readingMidBigText() = three("setup_reading_mid", readingFew, 2)

    @Test fun readingDoneLight() {
        three("setup_reading_done", readFew, 0)
        rule.onNodeWithText("Try it now").assertIsDisplayed()
        rule.onNodeWithText("You can keep searching the content read so far.").assertIsDisplayed()
    }

    @Test fun readingDoneDark() = three("setup_reading_done", readFew, 1)

    @Test fun readingDoneBigText() = three("setup_reading_done", readFew, 2)

    @Test fun setupTuning() = setup("setup_tuning_dark", going.copy(model = ModelState.Done, tune = TuneState.Running), dark = true)

    @Test fun setupTuneFailed() = setup("setup_tune_failed_light", going.copy(model = ModelState.Done, tune = TuneState.Failed("x")))

    @Test fun setupFailed() = setup("setup_failed_light", going.copy(model = ModelState.Failed(spaceMessage(build))))

    @Test fun setupFilesDenied() = shot("setup_files_denied_light") { SetupContent(SetupUi(started = true, build = build), filesDenied = true) }

    @Test fun setupFellBack() = setup("setup_fellback_dark", going.copy(fellBack = true, model = ModelState.Downloading(0.1f, false)), dark = true)

    @Test fun setupFallbackOffer() = setup("setup_fallback_offer_light", going.copy(fellBack = true, downloadStopped = true))

    // --- Try it and the commit choice ---

    private val tryUi = readFew.copy(trying = true, estimate = null)
    private val scoped = SearchUi(setUp = true, scope = (1L..79L).toList(), unread = 31, photosRead = 12)
    private fun file(name: String, kind: Kind) = SourceFile("demo:$name", name, "Pictures", "image/png", kind, 120_000, 1_760_000_000_000)

    @Test fun tryItEmptyLight() {
        shot("setup_tryit_empty_light") { TryItContent(tryUi, scoped, SearchActions(), SetupActions()) }
        rule.onNodeWithText("Try it on your files").assertExists()
        rule.onNodeWithText("Search something you remember, or try one of these.").assertExists()
        rule.onNodeWithText("Continue").assertExists()
        rule.onAllNodesWithText("Settings").assertCountEquals(0)
    }

    /** On the device the suggestions arrived a frame after the kind chips and the swap crashed (ClassCastException). */
    @Test fun tryItSurvivesSuggestionsArrivingAfterKindChips() {
        val terms = mutableStateOf(emptyList<String>())
        val files = listOf(file("IMG_1.jpg", Kind.PHOTO), file("Screenshot_1.png", Kind.SCREENSHOT))
        rule.setContent { ShotTheme(false) { TryItContent(tryUi, scoped, SearchActions(), SetupActions(), files = files, suggestions = terms.value) } }
        rule.onNodeWithText("Photos").assertExists()
        terms.value = listOf("invoice", "deposit")
        rule.waitForIdle()
        rule.onNodeWithText("invoice").assertExists()
        rule.onAllNodesWithText("Photos").assertCountEquals(0)
    }

    @Test fun tryItEmptyDark() = shot("setup_tryit_empty_dark", dark = true) { TryItContent(tryUi, scoped, SearchActions(), SetupActions()) }

    @Test fun tryItEmptyBigText() = shot("setup_tryit_empty_text1_5", fontScale = 1.5f) { TryItContent(tryUi, scoped, SearchActions(), SetupActions()) }

    private val answered = scoped.answered(
        "boarding pass",
        listOf(
            SearchEngine.Hit(file("Screenshot_0812.png", Kind.SCREENSHOT), 1, 0, "", null, 1.0),
            SearchEngine.Hit(file("Itinerary_March.pdf", Kind.PDF), 2, 0, "p. 2", "boarding closes 40 min before departure", 0.9),
        ),
    )

    @Test fun tryItAfterSearchLight() {
        shot("setup_tryit_results_light") { SearchPane(answered, HomeUi(), SearchActions()) }
        rule.onNodeWithText("Closest in these 79 files").assertExists()
        rule.onNodeWithText("Still reading 31 more").assertExists()
        rule.onAllNodesWithText("Photos").assertCountEquals(0)
    }

    @Test fun tryItAfterSearchDark() = shot("setup_tryit_results_dark", dark = true) { SearchPane(answered, HomeUi(), SearchActions()) }

    @Test fun tryItAfterSearchBigText() = shot("setup_tryit_results_text1_5", fontScale = 1.5f) { SearchPane(answered, HomeUi(), SearchActions()) }

    @Test fun tryItDoneWhenNothingIsLeft() {
        shot("setup_tryit_done_light") { TryItContent(tryUi.copy(estimate = ReadEstimate(0, 0, 0, 0, true)), scoped, SearchActions(), SetupActions()) }
        rule.onNodeWithText("Done").assertExists()
    }

    private val commitUi = tryUi.copy(committing = true)

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
        rule.onNodeWithText("Continue").performClick()
        assertEquals(true, continued)
    }

    private val choosing = SetupUi(started = true, build = build)

    @Test fun setupAccessChoice() = setup("setup_access_choice_light", choosing)

    @Test fun setupAccessChoiceDark() = setup("setup_access_choice_dark", choosing, dark = true)

    private val lowMemory = SetupUi(build = build, textOnly = true, ramGb = 3.0)

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
