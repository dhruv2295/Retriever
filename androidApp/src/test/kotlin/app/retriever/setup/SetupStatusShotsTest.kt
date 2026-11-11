package app.retriever.setup

import android.os.storage.StorageManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import app.retriever.ShotTheme
import app.retriever.access.Access
import app.retriever.access.Photos
import app.retriever.access.PickedFolder
import app.retriever.core.Backend
import app.retriever.core.DescriptionState
import app.retriever.core.DeviceWords
import app.retriever.core.FileState
import app.retriever.core.IndexState
import app.retriever.core.Kind
import app.retriever.core.Prefs
import app.retriever.core.ReadingPolicy
import app.retriever.core.ReadingStatus
import app.retriever.core.SampleFile
import app.retriever.core.SamplePile
import app.retriever.core.SearchEngine
import app.retriever.core.SetupInputs
import app.retriever.core.SetupMark
import app.retriever.core.SetupStage
import app.retriever.core.SourceFile
import app.retriever.core.StopReason
import app.retriever.core.Store
import app.retriever.core.TasteProgress
import app.retriever.core.Words
import app.retriever.core.motion.MascotMood
import app.retriever.decidedStatus
import app.retriever.home.HomeUi
import app.retriever.search.SearchActions
import app.retriever.search.SearchPane
import app.retriever.search.SearchUi
import app.retriever.search.answered
import app.retriever.shot
import app.retriever.status.StatusActions
import app.retriever.status.StatusContent
import app.retriever.tallWindowForBigText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
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
        allFilesOff: Boolean = false,
    ) = shot(name, dark, fontScale, settleMs = if (ui.stage == SetupStage.WELCOME) 4_000 else 0) {
        SetupContent(ui, actions = actions, allFilesOff = allFilesOff, reduceMotion = true)
    }

    /** Light, dark and 1.5x text of one first-run state. */
    private fun three(name: String, ui: SetupUi, variant: Int) = when (variant) {
        0 -> setup("${name}_light", ui)
        1 -> setup("${name}_dark", ui, dark = true)
        else -> setup("${name}_text1_5", ui, fontScale = 1.5f)
    }

    private val previewFiles = (1..8).map { i ->
        SourceFile("demo:reading-$i", "Photo $i.jpg", "Photos", "image/jpeg", Kind.PHOTO, 1_200_000, 1_760_000_000_000)
    }

    @Before fun readingThumbnails() = registerReadingThumbnails(previewFiles.map { it.uri } + planFiles(0).map { it.file.uri })

    // --- Hello ---

    private val hello = SetupUi(downloadBytes = 388_000_000)
    private val modelPresent = hello.copy(modelPresent = true)
    private val lowMemory = hello.copy(textOnly = true)

    @Test fun helloLight() {
        three("hello", hello, 0)
        rule.onNodeWithText(Words.helloTitle(false)).assertIsDisplayed()
        rule.onNodeWithText(Words.HELLO_BODY).assertIsDisplayed()
        rule.onNodeWithText(Words.FOR_EXAMPLE).assertIsDisplayed()
        // Remove animations: the first example shows, whole, with its card.
        rule.onNodeWithText(Words.HELLO_EXAMPLES[0].query).assertIsDisplayed()
        rule.onNodeWithText(Words.HELLO_EXAMPLES[0].title).assertIsDisplayed()
        rule.onNodeWithText(Words.onDevice(DeviceWords.ANDROID)).assertIsDisplayed()
        rule.onNodeWithText(Words.GET_STARTED).assertIsDisplayed()
        // Get started downloads here, so the line over it says so.
        rule.onNodeWithText(Words.downloadLine("388 MB")).assertIsDisplayed()
        // No skip and no demo on the way in.
        listOf("Skip", "demo").forEach { rule.onAllNodesWithText(it, substring = true).assertCountEquals(0) }
    }

    @Test fun helloBigText() = three("hello", hello, 2)

    /** The model already on the phone: nothing to download, so no note. */
    @Test fun helloWithTheModelSaysNoDownload() {
        setup("hello_model_present_light", modelPresent)
        rule.onAllNodesWithText("download", substring = true).assertCountEquals(0)
    }

    /** Too little memory: the title promises only what the phone can do, and nothing downloads. */
    @Test fun helloTextOnly() {
        setup("hello_text_only_light", lowMemory)
        rule.onNodeWithText(Words.helloTitle(true)).assertIsDisplayed()
        rule.onAllNodesWithText("download", substring = true).assertCountEquals(0)
    }

    @Test fun getStartedCallsItsAction() {
        var started = false
        setup("hello_buttons", hello, actions = SetupActions(getStarted = { started = true }))
        rule.onNodeWithText(Words.GET_STARTED).performClick()
        assertTrue(started)
    }

    // --- Access ---

    private val access = SetupUi(setup = SetupInputs(SetupMark.GREETED))
    private val granted = SetupUi(setup = SetupInputs(SetupMark.GREETED, accessGiven = true), access = Access.ALL)

    @Test fun accessLight() {
        setup("access_light", access)
        rule.onNodeWithText(Words.ACCESS_TITLE).assertIsDisplayed()
        rule.onNodeWithText(Words.ANDROID_ACCESS_BODY).assertIsDisplayed()
        rule.onNodeWithText(Words.onDevice(DeviceWords.ANDROID)).assertIsDisplayed()
        rule.onNodeWithText(Words.ALLOW_ACCESS).assertIsDisplayed()
        rule.onNodeWithText(Words.INCLUDE_TITLE).assertIsDisplayed()
        rule.onNodeWithText(Words.ALL_FILES_OFF).assertDoesNotExist()
        // The tiles picture access; they are not buttons.
        rule.onAllNodesWithText(Words.DOCUMENTS).assertCountEquals(0)
    }

    @Test fun accessBigText() = setup("access_text1_5", access, fontScale = 1.5f)

    @Test fun accessGrantedBigText() = setup("access_granted_text1_5", granted, fontScale = 1.5f)

    @Test fun accessButtonsCallTheirActions() {
        val calls = mutableListOf<String>()
        setup("access_buttons", access, actions = SetupActions(allowFiles = { calls += "files" }, chooseInstead = { calls += "choose" }))
        rule.onNodeWithText(Words.ALLOW_ACCESS).performClick()
        rule.onNodeWithText(Words.INCLUDE_TITLE).performClick()
        assertEquals(listOf("files", "choose"), calls)
    }

    /** Back from Settings with the switch still off: what to do, above the button. */
    @Test fun accessAfterAllFilesWasLeftOff() {
        setup("access_all_files_off_light", access, allFilesOff = true)
        rule.onNodeWithText(Words.ALL_FILES_OFF).assertExists()
        rule.onNodeWithText(Words.ALLOW_ACCESS).assertExists()
    }

    /** All-files access already on: Continue confirms it, with no trip to Settings. */
    @Test fun accessGrantedContinuesWithoutSettings() {
        val calls = mutableListOf<String>()
        setup("access_granted_light", granted, actions = SetupActions(allowFiles = { calls += "settings" }, continueFromAccess = { calls += "continue" }))
        rule.onNodeWithText(Words.ALLOW_ACCESS).assertDoesNotExist()
        rule.onNodeWithText(Words.ACCESS_SCANNING).assertIsDisplayed()
        rule.onAllNodesWithContentDescription(Words.ALLOWED).assertCountEquals(3)
        rule.onNodeWithText(Words.CONTINUE).performClick()
        assertEquals(listOf("continue"), calls)
    }

    @Test fun pickedFolderShowsScanningAndContinueWithoutClaimingWholePhoneAccess() {
        val selected = granted.copy(access = Access(false, folders = listOf(PickedFolder("content://picked", "Documents"))))
        setup("access_folder_granted_light", selected)
        rule.onNodeWithText(Words.ACCESS_SCANNING).assertIsDisplayed()
        rule.onNodeWithText(Words.CONTINUE).assertIsEnabled()
        rule.onNodeWithText(Words.INCLUDE_TITLE).assertExists()
        rule.onAllNodesWithContentDescription(Words.ALLOWED).assertCountEquals(0)
    }

    // --- Choose what to include ---

    private fun include(
        name: String,
        ui: SetupUi,
        asked: Boolean = false,
        folderError: String? = null,
        actions: SetupActions = SetupActions(),
        dark: Boolean = false,
    ) = shot(name, dark) { IncludeSheet(ui, asked, folderError, actions) }

    private val nothing = SetupUi()
    private val mediaGiven = SetupUi(setup = SetupInputs(accessGiven = true), access = Access(false, Photos.ALL, videos = true))

    @Test fun includeWaitsForAChoice() {
        include("setup_include_light", nothing)
        rule.onNodeWithText(Words.INCLUDE_TITLE).assertExists()
        rule.onNodeWithText(Words.PHOTOS_AND_VIDEOS).assertExists()
        rule.onNodeWithText(Words.kindLabelPlural(Kind.AUDIO)).assertExists()
        rule.onNodeWithText(Words.ADD_FOLDER).assertExists()
        rule.onNodeWithText(Words.CONTINUE).assertIsNotEnabled()
        rule.onNodeWithText(Words.ACCESS_SCANNING).assertDoesNotExist()
        rule.onNodeWithText(Words.NOTHING_SELECTED).assertDoesNotExist()
    }

    @Test fun includeAskedWithNothingGivenSaysWhatToDo() {
        include("setup_include_denied_light", nothing, asked = true, folderError = "Retriever already searches that folder.")
        rule.onNodeWithText(Words.NOTHING_SELECTED).assertExists()
        rule.onNodeWithText("Retriever already searches that folder.").assertExists()
    }

    @Test fun includeContinueConfirmsWhatWasGiven() {
        val calls = mutableListOf<String>()
        include(
            "setup_include_given_light",
            mediaGiven,
            actions = SetupActions(allowMedia = {
                calls += "media"
            }, addFolder = { calls += "folder" }, continueFromAccess = {
                calls +=
                    "continue"
            }),
        )
        rule.onNodeWithText(Words.ACCESS_SCANNING).assertIsDisplayed()
        rule.onNodeWithText(Words.PHOTOS_AND_VIDEOS).performClick()
        rule.onNodeWithText(Words.ADD_FOLDER).performClick()
        rule.onNodeWithText(Words.CONTINUE).assertIsEnabled().performClick()
        assertEquals(listOf("media", "folder", "continue"), calls)
    }

    // --- The demo ---

    /** [read] of a 30-file plan; name-only files are done without being read, so pending is what is left. */
    private fun progress(read: Int, pending: Int = 30 - read): TasteProgress {
        val plan = linkedMapOf(Kind.SCREENSHOT to 6, Kind.DOC to 6, Kind.PDF to 4, Kind.PHOTO to 12, Kind.VIDEO to 2)
        var left = read
        val byKind = plan.mapValues { (_, n) -> minOf(left, n).also { left -= it } to n }
        return TasteProgress(byKind, byKind.filterValues { it.first == it.second }.keys, read, pending, 30)
    }

    /** The plan's files, the first [read] of them read; every fourth one a document, the rest photos with previews. */
    private fun planFiles(read: Int) = (0 until 30).map { i ->
        val doc = i % 4 == 1
        val f = if (doc) {
            SourceFile("demo:doc-$i", "Notes $i.txt", "Documents", "text/plain", Kind.DOC, 2_000, 1_760_000_000_000)
        } else {
            previewFiles[i % previewFiles.size]
        }
        SampleFile(i, f.copy(uri = "${f.uri}#$i"), i < read, if (i < read) null else "Waiting")
    }

    private val chosen = SetupInputs(SetupMark.ACCESS_CHOSEN, planned = 30, accessGiven = true)
    private fun sample(read: Int, pending: Int = 30 - read, pause: String? = null) =
        SetupUi(setup = chosen, access = Access.ALL, taste = progress(read, pending), sample = planFiles(read), pauseReason = pause)

    /** Access chosen, the files not picked yet. */
    private val picking = SetupUi(setup = chosen.copy(planned = 0), access = Access.ALL, taste = TasteProgress(emptyMap(), emptySet(), 0, 0, 0))
    private val start = sample(0)
    private val processing = sample(12)
    private val paused = sample(12, pause = "Waits until you charge")

    /** 27 searchable inside and 3 name-only or couldn't open: the count and the bar still say 30 of 30. */
    private val done = sample(27, pending = 0)
    private val scoped = SearchUi(setUp = true, scope = (1L..30L).toList(), unread = 18, sampleDone = 12, samplePlanned = 30, photosRead = 12)
    private val terms = listOf("invoice", "deposit", "lease")

    private fun demo(
        name: String,
        ui: SetupUi,
        dark: Boolean = false,
        fontScale: Float = 1f,
        suggestions: List<String>? = terms,
        actions: SetupActions = SetupActions(),
        state: DescriptionState = DescriptionState.DOWNLOADING,
        percent: Int? = 64,
    ) {
        // The phone's own height at every text size: at 2.0 the body scrolls under the pinned footer.
        rule.setContent {
            ShotTheme(dark, fontScale) { DemoContent(ui, scoped, SearchActions(), actions, state, percent, suggestions = suggestions, reduceMotion = true) }
        }
        rule.shot(name)
    }

    @Test fun demoPicking() {
        demo("demo_picking_light", picking, suggestions = null)
        rule.onNodeWithText(Words.FIRST_LOOK).assertIsDisplayed()
        rule.onNodeWithText(Words.DEMO_PICKING).assertIsDisplayed()
        rule.onNodeWithText(Words.DEMO_HINT).assertIsDisplayed()
        rule.onAllNodesWithText(Words.readOf(0, 0)).assertCountEquals(0)
        // The pile says where things stand but opens nothing yet.
        rule.onNodeWithContentDescription(Words.DEMO_PICKING).assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        // Search works from the first frame, and Continue is never grey.
        rule.onNodeWithText(Words.firstSearchHint(DescriptionState.DOWNLOADING)).assertIsDisplayed()
        rule.onNodeWithText(Words.CONTINUE).assertIsEnabled()
    }

    /** Search by description still downloading: its line under the bar, and a hint that asks for words only. */
    @Test fun demoDownloading() {
        demo("first_search_downloading_light", processing)
        rule.onNodeWithText(Words.readOf(12, 30)).assertIsDisplayed()
        rule.onNodeWithText(Words.descriptionLine(DescriptionState.DOWNLOADING, 64)!!).assertIsDisplayed()
        rule.onNodeWithText(Words.firstSearchHint(DescriptionState.DOWNLOADING)).assertIsDisplayed()
        rule.onNodeWithText("invoice").assertIsDisplayed()
        rule.onAllNodesWithText(Words.DEMO_HINT).assertCountEquals(0)
        rule.onAllNodesWithText(Words.DESCRIPTION_READY).assertCountEquals(0)
        // No try, sample, Skip or Settings on the first search: Continue is the only way on.
        listOf("Try search", "Skip", "sample", "Settings").forEach { rule.onAllNodesWithText(it, substring = true).assertCountEquals(0) }
    }

    @Test fun demoProcessingLargestText() = demo("demo_processing_text2", processing, fontScale = 2f)

    @Test fun demoWaitingForWifi() {
        val calls = mutableListOf<String>()
        demo(
            "first_search_wifi_light",
            processing,
            state = DescriptionState.WAITING_FOR_WIFI,
            actions = SetupActions(useMobileData = { calls += "mobile" }),
        )
        rule.onNodeWithText(Words.descriptionLine(DescriptionState.WAITING_FOR_WIFI, null)!!).assertIsDisplayed()
        rule.onNodeWithText(Words.USE_MOBILE_DATA).performClick()
        assertEquals(listOf("mobile"), calls)
    }

    @Test fun demoDownloadFailedOffersTryAgain() {
        var retried = false
        demo("first_search_failed_light", processing, state = DescriptionState.FAILED, actions = SetupActions(retryDownload = { retried = true }))
        rule.onNodeWithText(Words.descriptionLine(DescriptionState.FAILED, null)!!).assertIsDisplayed()
        rule.onNodeWithText(Words.TRY_AGAIN).performClick()
        assertTrue(retried)
    }

    /** Refused for space: Android's storage screen, then Try again, as Settings' card offers. */
    @Test fun demoNoSpaceFreesUpSpaceThenTriesAgain() {
        var retried = false
        demo("first_search_no_space_light", processing, state = DescriptionState.NO_SPACE, actions = SetupActions(retryDownload = { retried = true }))
        rule.onNodeWithText(Words.descriptionLine(DescriptionState.NO_SPACE, null)!!).assertIsDisplayed()
        rule.onNodeWithText(Words.FREE_UP_SPACE).performClick()
        assertEquals(StorageManager.ACTION_MANAGE_STORAGE, shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity?.action)
        rule.onNodeWithText(Words.TRY_AGAIN).performClick()
        assertTrue(retried)
    }

    /** Ready: the banner at the top, no line under the bar, and the hint asks for a description. */
    @Test fun demoReady() {
        var finished = false
        demo("first_search_ready_light", done, state = DescriptionState.READY, actions = SetupActions(finish = { finished = true }))
        rule.onNodeWithText(Words.DESCRIPTION_READY).assertIsDisplayed()
        rule.onNodeWithText(Words.DESCRIPTION_READY_BODY).assertIsDisplayed()
        rule.onNodeWithText(Words.firstSearchHint(DescriptionState.READY)).assertIsDisplayed()
        rule.onAllNodesWithText("search by description", substring = true).assertCountEquals(0)
        // Name-only and couldn't-open files count: 30 of 30, not 27.
        rule.onNodeWithText(Words.readOf(30, 30)).assertIsDisplayed()
        rule.onNodeWithText(Words.CONTINUE).performClick()
        assertTrue(finished)
    }

    /** A phone too small for search by description: no line about it, and the hint asks for words. */
    @Test fun demoTextOnly() {
        demo("first_search_text_only_light", processing, state = DescriptionState.UNAVAILABLE)
        rule.onAllNodesWithText("search by description", substring = true).assertCountEquals(0)
        rule.onNodeWithText(Words.firstSearchHint(DescriptionState.UNAVAILABLE)).assertIsDisplayed()
    }

    /** A stop says why in the quiet line, before anything about the download, and offers nothing to tap there. */
    @Test fun demoPausedSaysWhy() {
        demo("first_search_paused_light", paused, state = DescriptionState.WAITING_FOR_WIFI)
        rule.onNodeWithText("Waits until you charge").assertIsDisplayed()
        rule.onAllNodesWithText(Words.descriptionLine(DescriptionState.WAITING_FOR_WIFI, null)!!).assertCountEquals(0)
        rule.onAllNodesWithText(Words.USE_MOBILE_DATA).assertCountEquals(0)
    }

    /** Fewer than three suggestions: the hint holds the slot. */
    @Test fun demoHintUntilThreeSuggestions() {
        demo("demo_hint_light", processing, suggestions = terms.take(2))
        rule.onNodeWithText(Words.DEMO_HINT).assertIsDisplayed()
        rule.onAllNodesWithText("invoice").assertCountEquals(0)
    }

    /** The spec's check on a 6.3" phone at default text: title, count, pile, chips, the field and Continue, no scroll. */
    @Test fun demoFitsWithoutScrolling() {
        demo("demo_fit_light", processing)
        val field = rule.onNodeWithText(Words.firstSearchHint(DescriptionState.DOWNLOADING)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        rule.onNodeWithText(Words.CONTINUE).assertIsDisplayed()
        val count = Words.readOf(12, 30)
        val rows = listOf(
            rule.onNodeWithText(Words.FIRST_LOOK),
            rule.onNodeWithContentDescription(count),
            rule.onNodeWithText(count),
            rule.onNodeWithText("lease"),
        )
        for (node in rows) {
            val bounds = node.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$bounds above the footer $field", bounds.bottom <= field.top)
        }
    }

    /**
     * At twice the text the body scrolls under the pinned footer once it no longer fits. On a 411 × 891 window it still
     * fits (demo_processing_text2), so this is a smaller phone's height.
     */
    @Test fun demoScrollsAtLargestText() {
        RuntimeEnvironment.setQualifiers("+h640dp")
        demo("demo_scroll_text2", processing, fontScale = 2f)
        val continueTop = rule.onNodeWithText(Words.CONTINUE).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        rule.onNodeWithText("lease").assertIsNotDisplayed().performScrollTo().assertIsDisplayed()
        assertEquals(continueTop, rule.onNodeWithText(Words.CONTINUE).fetchSemanticsNode().boundsInRoot.top)
    }

    /** A cold relaunch mid-demo: progress arrives before the files, and the files processed by then land without settling. */
    @Test fun theFirstDeliveryNeverReplaysThePile() {
        val ui = mutableStateOf(processing.copy(sample = null))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ShotTheme(false) { DemoContent(ui.value, scoped, SearchActions(), SetupActions(), DescriptionState.DOWNLOADING, null, reduceMotion = false) }
        }
        rule.mainClock.advanceTimeByFrame()
        ui.value = processing
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        fun top() = rule.onNodeWithTag("card:11", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val first = top()
        rule.mainClock.advanceTimeBy(2_000)
        assertEquals(top(), first)
    }

    @Test fun seeAllOpensFromThePile() {
        var opened = 0
        demo("demo_see_all", processing, actions = SetupActions(seeAll = { opened++ }))
        rule.onNodeWithContentDescription(Words.readOf(12, 30)).performClick()
        assertEquals(1, opened)
    }

    @Test fun theMascotWalksOnlyWhileTheDemoProcesses() {
        assertEquals(MascotMood.WALKING, start.mood())
        assertEquals(MascotMood.WALKING, picking.mood())
        assertEquals(MascotMood.WALKING, processing.mood())
        assertEquals(MascotMood.RESTING, paused.mood())
        assertEquals(MascotMood.RESTING, done.mood())
        assertEquals(MascotMood.WELCOME, hello.mood())
        assertEquals(MascotMood.SEARCHING, access.mood())
    }

    /** Before core's first progress: the title, the pile's placeholders and the count, never blank. */
    @Test fun theDemoNeverStartsBlank() {
        demo("demo_no_progress_light", picking.copy(taste = null), suggestions = null)
        rule.onNodeWithText(Words.FIRST_LOOK).assertIsDisplayed()
        rule.onNodeWithText(Words.DEMO_PICKING).assertIsDisplayed()
    }

    private fun top(text: String) = rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    /** Every row in place from the first frame: picking, processing and done stand alike. */
    @Test fun theDemoNeverShifts() {
        val ui = mutableStateOf(picking)
        rule.setContent {
            ShotTheme(false) {
                DemoContent(ui.value, scoped, SearchActions(), SetupActions(), DescriptionState.DOWNLOADING, 64, suggestions = null, reduceMotion = true)
            }
        }
        val countTop = top(Words.DEMO_PICKING)
        val hintTop = top(Words.DEMO_HINT)
        for (state in listOf(start, processing, done)) {
            ui.value = state
            rule.waitForIdle()
            assertEquals(countTop, top(Words.readOf(state.taste!!.done.toLong(), 30)))
            assertEquals(hintTop, top(Words.DEMO_HINT))
        }
    }

    /** A pile narrower than its full size keeps its shape: each card's offset shrinks with the cards, as on iOS. */
    @Test fun pileSlotsShrinkWithThePile() {
        val cards = planFiles(3).take(3)
        // Away from the window's edge: at core's full size a turned card reaches past the narrow pile, and the edge would cut its bounds.
        rule.setContent {
            ShotTheme(false) {
                Box(Modifier.padding(48.dp).width(96.dp)) { SamplePile(cards, emptyList(), true, "pile", "see", null) }
            }
        }
        fun centre(i: Int) = rule.onNodeWithTag("card:$i", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
        val scale = 96f / SamplePile.FOOTPRINT_DP
        val dp = rule.density.density
        assertEquals((SamplePile.dx(2) - SamplePile.dx(0)) * scale, (centre(2).x - centre(0).x) / dp, 0.5f)
        assertEquals((SamplePile.dy(2) - SamplePile.dy(0)) * scale, (centre(2).y - centre(0).y) / dp, 0.5f)
    }

    private fun file(name: String, kind: Kind) = SourceFile("demo:$name", name, "Pictures", "image/png", kind, 120_000, 1_760_000_000_000)

    private val answered = scoped.answered(
        "boarding pass",
        listOf(
            SearchEngine.Hit(file("Screenshot_0812.png", Kind.SCREENSHOT), 1, 0, "", null, 1.0),
            SearchEngine.Hit(file("Itinerary_March.pdf", Kind.PDF), 2, 0, "p. 2", "boarding closes 40 min before departure", 0.9),
        ),
    )

    /** Above the demo's results: names cover the whole phone, the words inside only the demo files processed so far. */
    @Test fun demoResultsSayWhatTheySearch() {
        shot("demo_results_light") { SearchPane(answered, HomeUi(), SearchActions()) }
        rule.onNodeWithText(Words.demoSearchScope(12, finished = false, words = DeviceWords.ANDROID)).assertExists()
        rule.onAllNodesWithText("Photos").assertCountEquals(0)
    }

    @Test fun demoResultsOnceDone() {
        shot("demo_results_done_light") { SearchPane(answered.copy(unread = 0, sampleDone = 30), HomeUi(), SearchActions()) }
        rule.onNodeWithText(Words.demoSearchScope(30, finished = true, words = DeviceWords.ANDROID)).assertExists()
    }

    /** Spare height is split above and below the group, not left as a band under it. */
    @Test fun spareHeightIsSplitAboveAndBelow() {
        RuntimeEnvironment.setQualifiers("+h1500dp")
        val height = mutableStateOf(1_000.dp)
        rule.setContent { ShotTheme(false) { Box(Modifier.height(height.value)) { SetupContent(access, reduceMotion = true) } } }
        val before = top(Words.ACCESS_TITLE)
        height.value = 1_200.dp
        rule.waitForIdle()
        assertEquals(100f, (top(Words.ACCESS_TITLE) - before) / rule.density.density, 2f)
    }

    private val prefs = Prefs(excludedFolders = setOf("Android/media/WhatsApp/Stickers"))
    private val reading = app.retriever.readCounts(
        mapOf(FileState.INDEXED to 3_412L, FileState.PENDING to 8_118L, FileState.SKIPPED to 3L),
        mapOf(Kind.PHOTO to 9_000L, Kind.PDF to 2_533L),
        tailChunks = 12,
    )

    private val running = IndexState(
        running = true,
        processed = 120,
        pendingAtStart = 8_238,
        current = "IMG_20250914_183022.jpg",
        currentFile = previewFiles.first(),
        recentFiles = previewFiles,
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
        tall: Boolean = false,
        prefs: Prefs = this.prefs,
        readingStatus: ReadingStatus = decidedStatus(counts, prefs, index),
    ) = shot(name, dark, fontScale, tall) {
        StatusContent(
            counts, prefs, index, skippedFiles, open, batteryHint = true,
            actions = StatusActions(openSettings = {}), access = access, readingStatus = readingStatus,
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

    @Test fun statusCoverageFolders() = coverage("status_coverage_folders_light", withFolders, dark = false)

    private val textOnlyCounts = app.retriever.readCounts(mapOf(FileState.INDEXED to 2_140L, FileState.PENDING to 310L), mapOf(Kind.PDF to 900L), 0)

    @Test fun statusTextOnly() = status("status_text_only_light", textOnlyCounts, idle, tall = true)

    private val tail = reading.copy(tailFiles = 7)

    @Test fun statusTailFiles() = status("status_tail_files_light", tail, running.copy(running = false, lastStop = StopReason.BUDGET))

    @Test fun statusHoldsNoPreferenceAndLinksToSettings() {
        var opened = 0
        shot("status_links_settings_light", tall = true) {
            StatusContent(
                caughtUp,
                prefs,
                idle,
                skippedFiles,
                false,
                batteryHint = false,
                actions = StatusActions(openSettings = { opened++ }),
                readingStatus = decidedStatus(caughtUp, prefs, idle),
            )
        }
        listOf("Only read while charging", "Screenshots", "PDFs", "Tune again", "Folders Retriever skips", "Read inside", "Sample mode")
            .forEach { rule.onAllNodesWithText(it, substring = true).assertCountEquals(0) }
        rule.onNodeWithText("Settings").performClick()
        assertEquals(1, opened)
    }

    @Test fun statusReading() {
        status("clarity_status_reading_light", tall = true)
        rule.onNodeWithText(Words.READING_STATUS).assertExists()
        rule.onNodeWithText("Processing your files").assertExists()
        // The count and Details' breakdown are the same numbers: its parts add up to the count's X, and "to go" is Y − X.
        val p = reading.progress
        rule.onNodeWithText(Words.readOf(p.processed, p.libraryTotal)).assertExists()
        for (row in Words.breakdown(p)) rule.onNodeWithText(row.label).assertExists()
        rule.onNodeWithText(Words.breakdown(p).single { it.label == "Still to process" }.count, useUnmergedTree = true).assertExists()
        rule.onAllNodesWithText("% done", substring = true).assertCountEquals(0)
        // Reading now keeps the file's name beside a small tile.
        rule.onNodeWithText(previewFiles.first().name).assertExists()
        rule.onNodeWithText("Pause").assertExists()
        rule.onNodeWithText("Details").assertExists()
    }

    @Test fun statusReadingLargestText() = status("clarity_status_reading_text2", fontScale = 2f)

    private val pausedIndex = running.copy(running = false, currentFile = null)

    @Test fun statusPaused() {
        status("clarity_status_paused_light", index = pausedIndex, prefs = prefs.copy(paused = true), tall = true)
        rule.onNodeWithText("Paused").assertExists()
        rule.onNodeWithText("Resume").assertExists()
        rule.onNodeWithText("Pause").assertDoesNotExist()
    }

    @Test fun statusBatteryPreferenceCanResume() {
        val paused = ReadingPolicy.decide(
            reading.progress,
            StopReason.UNPLUGGED,
            running = false,
            reading = false,
            DeviceWords.ANDROID,
            battery = 60,
            processingStarted = true,
        )
        status("processing_battery_pause_light", index = pausedIndex, readingStatus = paused, tall = true)
        rule.onNodeWithText(paused.title).assertExists()
        rule.onNodeWithText(Words.strings.automaticProcessingPreferenceDetail).assertExists()
        rule.onNodeWithText("Resume").assertIsEnabled()
        rule.onNodeWithText("Pause").assertDoesNotExist()
        rule.onNodeWithText(Words.strings.processingSearchAvailableDetail).assertExists()
    }

    @Test fun statusThermalWaitDoesNotPromiseResume() {
        val hot = ReadingPolicy.decide(reading.progress, StopReason.HOT, false, false, DeviceWords.ANDROID)
        status("processing_thermal_wait_text2", index = pausedIndex, readingStatus = hot, fontScale = 2f, tall = true)
        rule.onNodeWithText(hot.title).assertExists()
        rule.onNodeWithText(Words.strings.hotDetail).assertExists()
        rule.onNodeWithText("Resume").assertDoesNotExist()
        rule.onNodeWithText("Pause").assertDoesNotExist()
        rule.onNodeWithText(Words.strings.processingKeepPaused).assertExists()
    }

    @Test fun statusQueuedIsReadyAndCanResume() {
        val waiting = ReadingPolicy.decide(reading.progress, null, false, false, DeviceWords.ANDROID, processingStarted = true)
        status("processing_ready_light", index = pausedIndex, readingStatus = waiting, tall = true)
        rule.onNodeWithText(waiting.title).assertExists()
        rule.onNodeWithText("Resume").assertIsEnabled()
        rule.onNodeWithText("Pause").assertDoesNotExist()
    }

    @Test fun statusAdaptiveProcessingCanPauseAndKeepsCoverageSeparate() {
        val counts = reading.copy(progress = reading.progress.copy(read = 100, partlyRead = 7, nameOnly = 11))
        val active = ReadingPolicy.decide(counts.progress, null, true, true, DeviceWords.ANDROID, adaptive = true)
        status("processing_adaptive_coverage_light", counts = counts, readingStatus = active, tall = true)
        rule.onNodeWithText(Words.strings.processingAdaptiveDetail).assertExists()
        rule.onNodeWithText("Pause").assertIsEnabled()
        for (row in Words.breakdown(counts.progress)) {
            rule.onNodeWithText(row.label).assertExists()
            rule.onNodeWithText(row.count, useUnmergedTree = true).assertExists()
        }
        rule.onNodeWithText("fully searchable", substring = true).assertDoesNotExist()
    }

    @Test fun statusSkippedOpen() = status("status_skipped_open_light", index = running.copy(running = false, lastStop = StopReason.UNPLUGGED), open = true)

    @Test fun statusCaughtUp() = status(
        "status_caught_up_dark",
        counts = app.retriever.readCounts(mapOf(FileState.INDEXED to 11_530L), mapOf(Kind.PHOTO to 9_000L), 0),
        index = IndexState(lastStop = StopReason.DONE, backend = Backend.GPU),
        dark = true,
    )
}
