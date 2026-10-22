package app.retriever.setup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import app.retriever.core.DeviceWords
import app.retriever.core.FileState
import app.retriever.core.IndexState
import app.retriever.core.Kind
import app.retriever.core.Prefs
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

    private val welcome = SetupUi()

    @Test fun welcomeLight() {
        three("clarity_welcome", welcome, 0)
        rule.onNodeWithText(Words.WELCOME_TITLE).assertExists()
        rule.onNodeWithText(Words.WELCOME_BODY).assertExists()
        // The privacy promise is its own line.
        rule.onNodeWithText(Words.nothingSent(DeviceWords.ANDROID)).assertExists()
        rule.onNodeWithText(Words.wholeDeviceNote(DeviceWords.ANDROID)).assertExists()
        rule.onNodeWithText(Words.SEARCH_WHOLE_PHONE).assertExists()
        rule.onNodeWithText(Words.CHOOSE_INSTEAD).assertExists()
        rule.onNodeWithText(Words.welcomeNext(DeviceWords.ANDROID)).assertExists()
        rule.onNodeWithText(Words.welcomeAfterDemo(DeviceWords.ANDROID)).assertExists()
        rule.onNodeWithText(Words.SKIP_THE_DEMO).assertExists()
        rule.onNodeWithText(Words.ALL_FILES_OFF).assertDoesNotExist()
        // No download on the way in.
        rule.onAllNodesWithText("MB", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("download", substring = true, ignoreCase = true).assertCountEquals(0)
    }

    @Test fun welcomeDark() = three("clarity_welcome", welcome, 1)

    @Test fun welcomeBigText() = three("clarity_welcome", welcome, 2)

    @Test fun welcomeButtonsCallTheirActions() {
        val calls = mutableListOf<String>()
        val actions = SetupActions(allowFiles = { calls += "files" }, chooseInstead = { calls += "choose" }, skipDemo = { calls += "skip" })
        setup("setup_welcome_buttons", welcome, actions = actions)
        rule.onNodeWithText(Words.SEARCH_WHOLE_PHONE).performClick()
        rule.onNodeWithText(Words.CHOOSE_INSTEAD).performScrollTo().performClick()
        rule.onNodeWithText(Words.SKIP_THE_DEMO).performClick()
        assertEquals(listOf("files", "choose", "skip"), calls)
    }

    /** Back from Settings with the switch still off: what to do, under the button. */
    @Test fun welcomeAfterAllFilesWasLeftOff() {
        setup("setup_welcome_all_files_off_light", welcome, allFilesOff = true)
        rule.onNodeWithText(Words.ALL_FILES_OFF).assertExists()
        rule.onNodeWithText(Words.SEARCH_WHOLE_PHONE).assertExists()
    }

    private val granted = SetupUi(setup = SetupInputs(accessGiven = true), access = Access.ALL)

    /** All-files access already on: Start the demo confirms it, with no trip to Settings. */
    @Test fun welcomeWithAllFilesStartsTheDemoWithoutSettings() {
        val calls = mutableListOf<String>()
        setup("demo_welcome_light", granted, actions = SetupActions(allowFiles = { calls += "settings" }, continueFromAccess = { calls += "continue" }))
        rule.onNodeWithText(Words.WHOLE_PHONE_READY).assertExists()
        rule.onNodeWithText(Words.SEARCH_WHOLE_PHONE).assertDoesNotExist()
        rule.onNodeWithText(Words.welcomeNext(DeviceWords.ANDROID)).assertExists()
        rule.onNodeWithText(Words.SKIP_THE_DEMO).assertExists()
        rule.onNodeWithText(Words.START_THE_DEMO).performClick()
        assertEquals(listOf("continue"), calls)
    }

    /** At font 1.0 on a 411×891 phone both choices show without scrolling: they are pinned below the welcome. */
    @Test fun welcomeShowsBothChoicesWithoutScrolling() {
        setup("demo_welcome_fits_light", granted)
        rule.onNodeWithText(Words.START_THE_DEMO).assertIsDisplayed()
        rule.onNodeWithText(Words.SKIP_THE_DEMO).assertIsDisplayed()
        // Both sentences of the demo paragraph sit fully above the pinned bar, with no scrolling. The bar starts 8 dp above its buttons.
        val barTop = rule.onNodeWithText(Words.START_THE_DEMO).getUnclippedBoundsInRoot().top - 8.dp
        listOf(Words.welcomeNext(DeviceWords.ANDROID), Words.welcomeAfterDemo(DeviceWords.ANDROID)).forEach { text ->
            val node = rule.onNodeWithText(text)
            node.assertIsDisplayed()
            assertTrue("$text ends at ${node.getUnclippedBoundsInRoot().bottom}, buttons start at $barTop", node.getUnclippedBoundsInRoot().bottom <= barTop)
        }
    }

    @Test fun welcomeReadyDark() = setup("demo_welcome_dark", granted, dark = true)

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
        rule.onNodeWithText(Words.NOTHING_SELECTED).assertDoesNotExist()
    }

    @Test fun includeDark() = include("setup_include_dark", mediaGiven, dark = true)

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
        // The time left is measured once files are processed: none before the first.
        val secondsLeft = when {
            read == 0 -> null
            pending == 0 -> 0
            else -> 75
        }
        return TasteProgress(byKind, byKind.filterValues { it.first == it.second }.keys, read, secondsLeft, pending, 30)
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
        SetupUi(setup = chosen, access = Access.ALL, taste = progress(read, pending), sample = planFiles(read), pauseReason = pause, rest = 25_763)

    /** Access chosen, the files not picked yet. */
    private val picking = SetupUi(setup = chosen.copy(planned = 0), access = Access.ALL, taste = TasteProgress(emptyMap(), emptySet(), 0, null, 0, 0))
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
    ) {
        // The phone's own height at every text size: at 2.0 the body scrolls under the pinned footer.
        rule.setContent { ShotTheme(dark, fontScale) { DemoContent(ui, scoped, SearchActions(), actions, suggestions = suggestions, reduceMotion = true) } }
        rule.shot(name)
    }

    @Test fun demoPicking() {
        demo("demo_picking_light", picking, suggestions = null)
        rule.onNodeWithText(Words.demoTitle(0)).assertIsDisplayed()
        rule.onNodeWithText(Words.DEMO_PICKING).assertIsDisplayed()
        rule.onNodeWithText(Words.DEMO_PICKING_NOTE).assertIsDisplayed()
        rule.onNodeWithText(Words.DEMO_HINT).assertIsDisplayed()
        rule.onAllNodesWithText(Words.readOf(0, 0)).assertCountEquals(0)
        rule.onAllNodesWithText(Words.seeAll(0)).assertCountEquals(0)
        // The pile says where things stand but opens nothing yet.
        rule.onNodeWithContentDescription(Words.DEMO_PICKING).assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        // Search works from the first frame, and Continue is never grey.
        rule.onNodeWithText(Words.SEARCH_DEMO).assertIsDisplayed()
        rule.onNodeWithText(Words.CONTINUE).assertIsEnabled()
    }

    @Test fun demoPickingDark() = demo("demo_picking_dark", picking, dark = true, suggestions = null)

    @Test fun demoProcessing() {
        demo("demo_processing_light", processing)
        rule.onNodeWithText(Words.demoTitle(30)).assertIsDisplayed()
        rule.onNodeWithText(Words.readOf(12, 30)).assertIsDisplayed()
        rule.onNodeWithText(Words.demoNote(75)).assertIsDisplayed()
        rule.onNodeWithText("invoice").assertIsDisplayed()
        rule.onAllNodesWithText(Words.DEMO_HINT).assertCountEquals(0)
        rule.onNodeWithText(Words.seeAll(30)).assertIsDisplayed()
        // No try, sample, Skip or Settings on the demo: Continue is the only way on.
        listOf("Try search", "Skip", "sample", "Settings").forEach { rule.onAllNodesWithText(it, substring = true).assertCountEquals(0) }
    }

    @Test fun demoProcessingDark() = demo("demo_processing_dark", processing, dark = true)

    @Test fun demoProcessingLargestText() = demo("demo_processing_text2", processing, fontScale = 2f)

    /** Fewer than three suggestions: the hint holds the slot. */
    @Test fun demoHintUntilThreeSuggestions() {
        demo("demo_hint_light", processing, suggestions = terms.take(2))
        rule.onNodeWithText(Words.DEMO_HINT).assertIsDisplayed()
        rule.onAllNodesWithText("invoice").assertCountEquals(0)
    }

    @Test fun demoPaused() {
        demo("demo_paused_light", paused)
        rule.onNodeWithText("Waits until you charge").assertIsDisplayed()
        rule.onAllNodesWithText("left.", substring = true).assertCountEquals(0)
        rule.onNodeWithText(Words.readOf(12, 30)).assertIsDisplayed()
    }

    @Test fun demoPausedDark() = demo("demo_paused_dark", paused, dark = true)

    @Test fun demoDone() {
        var continued = false
        demo("demo_done_light", done, actions = SetupActions(continueToCommit = { continued = true }))
        // Name-only and couldn't-open files count: 30 of 30, not 27.
        rule.onNodeWithText(Words.readOf(30, 30)).assertIsDisplayed()
        rule.onNodeWithText(Words.demoDone(25_763, DeviceWords.ANDROID)).assertIsDisplayed()
        rule.onAllNodesWithText("left.", substring = true).assertCountEquals(0)
        rule.onNodeWithText(Words.CONTINUE).performClick()
        assertTrue(continued)
    }

    @Test fun demoDoneDark() = demo("demo_done_dark", done, dark = true)

    /** The spec's check on a 6.3" phone at default text: title, pile, count, chips, the field and Continue, no scroll. */
    @Test fun demoFitsWithoutScrolling() {
        demo("demo_fit_light", processing)
        val field = rule.onNodeWithText(Words.SEARCH_DEMO).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        rule.onNodeWithText(Words.CONTINUE).assertIsDisplayed()
        val count = Words.readOf(12, 30)
        val rows = listOf(
            rule.onNodeWithText(Words.demoTitle(30)),
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
        rule.onNodeWithText(Words.seeAll(30)).assertIsNotDisplayed().performScrollTo().assertIsDisplayed()
        assertEquals(continueTop, rule.onNodeWithText(Words.CONTINUE).fetchSemanticsNode().boundsInRoot.top)
    }

    /** A cold relaunch mid-demo: progress arrives before the files, and the files processed by then land without settling. */
    @Test fun theFirstDeliveryNeverReplaysThePile() {
        val ui = mutableStateOf(processing.copy(sample = null))
        rule.mainClock.autoAdvance = false
        rule.setContent { ShotTheme(false) { DemoContent(ui.value, scoped, SearchActions(), SetupActions(), reduceMotion = false) } }
        rule.mainClock.advanceTimeByFrame()
        ui.value = processing
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        fun top() = rule.onNodeWithTag("card:11", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val first = top()
        rule.mainClock.advanceTimeBy(2_000)
        assertEquals(top(), first)
    }

    @Test fun seeAllOpensFromThePileAndTheButton() {
        var opened = 0
        demo("demo_see_all", processing, actions = SetupActions(seeAll = { opened++ }))
        rule.onNodeWithContentDescription(Words.readOf(12, 30)).performClick()
        rule.onNodeWithText(Words.seeAll(30)).performClick()
        assertEquals(2, opened)
    }

    @Test fun theMascotWalksOnlyWhileTheDemoProcesses() {
        assertEquals(MascotMood.WALKING, start.mood())
        assertEquals(MascotMood.WALKING, picking.mood())
        assertEquals(MascotMood.WALKING, processing.mood())
        assertEquals(MascotMood.RESTING, paused.mood())
        assertEquals(MascotMood.RESTING, done.mood())
        assertEquals(MascotMood.WELCOME, welcome.mood())
    }

    /** Before core's first progress: the title, the pile's placeholders, the count and the note, never blank. */
    @Test fun theDemoNeverStartsBlank() {
        demo("demo_no_progress_light", picking.copy(taste = null), suggestions = null)
        rule.onNodeWithText(Words.demoTitle(0)).assertIsDisplayed()
        rule.onNodeWithText(Words.DEMO_PICKING).assertIsDisplayed()
        rule.onNodeWithText(Words.DEMO_PICKING_NOTE).assertIsDisplayed()
    }

    private fun top(text: String) = rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    /** Every row in place from the first frame: picking, processing and done stand alike. */
    @Test fun theDemoNeverShifts() {
        val ui = mutableStateOf(picking)
        rule.setContent { ShotTheme(false) { DemoContent(ui.value, scoped, SearchActions(), SetupActions(), suggestions = null, reduceMotion = true) } }
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
        rule.setContent { ShotTheme(false) { Box(Modifier.width(96.dp)) { SamplePile(cards, emptyList(), true, "pile", "see", null) } } }
        fun centre(i: Int) = rule.onNodeWithTag("card:$i", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
        val scale = 96f / SamplePile.FOOTPRINT_DP
        val dp = rule.density.density
        assertEquals(SamplePile.dx(2) * scale, (centre(2).x - centre(0).x) / dp, 0.5f)
        assertEquals(SamplePile.dy(2) * scale, (centre(2).y - centre(0).y) / dp, 0.5f)
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

    @Test fun demoResultsDark() = shot("demo_results_dark", dark = true) { SearchPane(answered, HomeUi(), SearchActions()) }

    // --- When to process the rest ---

    private val afterDemo = done.copy(setup = chosen.copy(mark = SetupMark.CHOOSING))
    private val afterSkip = SetupUi(setup = SetupInputs(SetupMark.ACCESS_CHOSEN, skipped = true, accessGiven = true), access = Access.ALL, rest = 25_793)

    @Test fun chooseAfterTheDemo() {
        shot("demo_choose_after_demo_light") { SetupContent(afterDemo) }
        rule.onNodeWithText(Words.demoEnded(30, 30, skipped = false)!!).assertExists()
        rule.onNodeWithText(Words.chooseTitle(DeviceWords.ANDROID)).assertExists()
        rule.onNodeWithText(Words.chooseBody(25_763)).assertExists()
        rule.onNodeWithText(Words.choosePace(DeviceWords.ANDROID)).assertExists()
        rule.onNodeWithText(Words.RECOMMENDED).assertExists()
        // No sessions, schedules or "Done" on a button that starts hours of work.
        listOf("session", "schedule", "library").forEach { rule.onAllNodesWithText(it, substring = true).assertCountEquals(0) }
        rule.onAllNodesWithText(Words.DONE).assertCountEquals(0)
        rule.onNodeWithText(Words.START_PROCESSING).assertExists()
    }

    @Test fun chooseAfterTheDemoDark() = shot("demo_choose_after_demo_dark", dark = true) { SetupContent(afterDemo) }

    @Test fun chooseAfterAStoppedDemo() {
        shot("demo_choose_stopped_light") { SetupContent(processing.copy(setup = chosen.copy(mark = SetupMark.CHOOSING))) }
        rule.onNodeWithText(Words.demoEnded(12, 30, skipped = false)!!).assertExists()
    }

    @Test fun chooseAfterSkip() {
        shot("demo_choose_after_skip_light") { SetupContent(afterSkip) }
        rule.onAllNodesWithText("Demo", substring = true).assertCountEquals(0)
        rule.onNodeWithText(Words.chooseBody(25_793)).assertExists()
    }

    @Test fun chooseAfterSkipDark() = shot("demo_choose_after_skip_dark", dark = true) { SetupContent(afterSkip) }

    /** Before the phone's files are all counted, no number that still grows. */
    @Test fun chooseBeforeTheCount() {
        shot("demo_choose_uncounted_light") { SetupContent(afterSkip.copy(rest = null)) }
        rule.onNodeWithText(Words.chooseBody(null)).assertExists()
    }

    @Test fun chooseBigText() = shot("demo_choose_text1_5", fontScale = 1.5f) { SetupContent(afterDemo) }

    /** Spare height is split above and below the group, not left as a band under it. */
    @Test fun spareHeightIsSplitAboveAndBelow() {
        RuntimeEnvironment.setQualifiers("+h1500dp")
        val height = mutableStateOf(1_000.dp)
        rule.setContent { ShotTheme(false) { Box(Modifier.height(height.value)) { SetupContent(afterSkip, reduceMotion = true) } } }
        val title = Words.chooseTitle(DeviceWords.ANDROID)
        val before = top(title)
        height.value = 1_200.dp
        rule.waitForIdle()
        assertEquals(100f, (top(title) - before) / rule.density.density, 2f)
    }

    @Test fun threeOptionsCallTheirActions() {
        val calls = mutableListOf<String>()
        val actions = SetupActions(commit = { calls += "commit($it)" }, decideLater = { calls += "later" })
        shot("setup_commit_buttons") { SetupContent(afterDemo, actions = actions) }
        rule.onNodeWithText("While charging").performClick()
        assertEquals(emptyList<String>(), calls)
        rule.onNodeWithText(Words.START_PROCESSING).performClick()
        rule.onNodeWithText(Words.READ_15_NOW).performClick()
        rule.onNodeWithText(Words.START_PROCESSING).performClick()
        rule.onNodeWithText(Words.NOT_NOW).performClick()
        rule.onNodeWithText(Words.GO_TO_HOME).performClick()
        assertEquals(listOf("commit(true)", "commit(false)", "later"), calls)
    }

    /** Radio rows: one selectable group, each row a radio button, so a screen reader says "1 of 3, selected". */
    @Test fun theOptionsAreRadioRows() {
        rule.setContent { ShotTheme(false) { SetupContent(afterDemo) } }
        rule.onNodeWithText("While charging").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertIsSelected()
        rule.onNodeWithText(Words.NOT_NOW).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertIsNotSelected()
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
        prefs: Prefs = this.prefs,
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

    @Test fun statusReading() {
        status("clarity_status_reading_light", tall = true)
        rule.onNodeWithText(Words.READING_STATUS).assertExists()
        rule.onNodeWithText("Processing your files").assertExists()
        // The count and Details' breakdown are the same numbers: its parts add up to the count's X, and "to go" is Y − X.
        val p = reading.progress
        rule.onNodeWithText(Words.readOf(p.processed, p.libraryTotal)).assertExists()
        rule.onNodeWithText(Words.breakdown(p)).assertExists()
        rule.onAllNodesWithText("% done", substring = true).assertCountEquals(0)
        // Reading now keeps the file's name beside a small tile.
        rule.onNodeWithText(previewFiles.first().name).assertExists()
        rule.onNodeWithText("Pause").assertExists()
        rule.onNodeWithText("Details").assertExists()
    }

    @Test fun processingLight() = status("demo_status_light", tall = true)

    @Test fun processingDark() = status("demo_status_dark", dark = true, tall = true)

    @Test fun statusReadingDark() = status("clarity_status_reading_dark", dark = true, tall = true)

    @Test fun statusReadingLargestText() = status("clarity_status_reading_text2", fontScale = 2f)

    private val pausedIndex = running.copy(running = false, currentFile = null)

    @Test fun statusPaused() {
        status("clarity_status_paused_light", index = pausedIndex, prefs = prefs.copy(paused = true), tall = true)
        rule.onNodeWithText("Paused").assertExists()
        rule.onNodeWithText("Resume").assertExists()
        rule.onNodeWithText("Pause").assertDoesNotExist()
    }

    @Test fun statusPausedDark() = status("clarity_status_paused_dark", index = pausedIndex, prefs = prefs.copy(paused = true), dark = true, tall = true)

    @Test fun statusSkippedOpen() = status("status_skipped_open_light", index = running.copy(running = false, lastStop = StopReason.UNPLUGGED), open = true)

    @Test fun statusCaughtUp() = status(
        "status_caught_up_dark",
        counts = app.retriever.readCounts(mapOf(FileState.INDEXED to 11_530L), mapOf(Kind.PHOTO to 9_000L), 0),
        index = IndexState(lastStop = StopReason.DONE, backend = Backend.GPU),
        dark = true,
    )
}
