package app.retriever.setup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
import app.retriever.core.UpgradeStage
import app.retriever.core.Words
import app.retriever.core.motion.MascotMood
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
        rule.onNodeWithText(Words.SEARCH_BY_NAME).assertExists()
        rule.onNodeWithText(Words.ALL_FILES_OFF).assertDoesNotExist()
        // No download on the way in.
        rule.onAllNodesWithText("MB", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("download", substring = true, ignoreCase = true).assertCountEquals(0)
    }

    @Test fun welcomeDark() = three("clarity_welcome", welcome, 1)

    @Test fun welcomeBigText() = three("clarity_welcome", welcome, 2)

    @Test fun welcomeButtonsCallTheirActions() {
        val calls = mutableListOf<String>()
        val actions = SetupActions(allowFiles = { calls += "files" }, chooseInstead = { calls += "choose" }, searchByName = { calls += "names" })
        setup("setup_welcome_buttons", welcome, actions = actions)
        rule.onNodeWithText(Words.SEARCH_WHOLE_PHONE).performScrollTo().performClick()
        rule.onNodeWithText(Words.CHOOSE_INSTEAD).performScrollTo().performClick()
        rule.onNodeWithText(Words.SEARCH_BY_NAME).performScrollTo().performClick()
        assertEquals(listOf("files", "choose", "names"), calls)
    }

    /** Back from Settings with the switch still off: what to do, under the button. */
    @Test fun welcomeAfterAllFilesWasLeftOff() {
        setup("setup_welcome_all_files_off_light", welcome, allFilesOff = true)
        rule.onNodeWithText(Words.ALL_FILES_OFF).assertExists()
        rule.onNodeWithText(Words.SEARCH_WHOLE_PHONE).assertExists()
    }

    /** All-files access already on: Continue confirms it, with no trip to Settings. */
    @Test fun welcomeWithAllFilesContinuesWithoutSettings() {
        val calls = mutableListOf<String>()
        val granted = SetupUi(setup = SetupInputs(accessGiven = true), access = Access.ALL)
        setup("setup_welcome_ready_light", granted, actions = SetupActions(allowFiles = { calls += "settings" }, continueFromAccess = { calls += "continue" }))
        rule.onNodeWithText(Words.WHOLE_PHONE_READY).assertExists()
        rule.onNodeWithText(Words.SEARCH_WHOLE_PHONE).assertDoesNotExist()
        rule.onNodeWithText(Words.CONTINUE).performScrollTo().performClick()
        assertEquals(listOf("continue"), calls)
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

    // --- The sample ---

    /** [read] of a 71-file plan; Name only files are done without being read, so pending is what is left. */
    private fun progress(read: Int, pending: Int = 71 - read): TasteProgress {
        val plan = linkedMapOf(Kind.SCREENSHOT to 15, Kind.DOC to 12, Kind.PDF to 10, Kind.PHOTO to 32, Kind.VIDEO to 2)
        var left = read
        val byKind = plan.mapValues { (_, n) -> minOf(left, n).also { left -= it } to n }
        // The time left is measured once files are read: none before the first.
        val secondsLeft = when {
            read == 0 -> null
            pending == 0 -> 0
            else -> 75
        }
        return TasteProgress(byKind, byKind.filterValues { it.first == it.second }.keys, read, secondsLeft, read >= 20, pending)
    }

    /** The plan's files, the first [read] of them read; every fourth one a document, the rest photos with previews. */
    private fun planFiles(read: Int) = (0 until 71).map { i ->
        val doc = i % 4 == 1
        val f = if (doc) {
            SourceFile("demo:doc-$i", "Notes $i.txt", "Documents", "text/plain", Kind.DOC, 2_000, 1_760_000_000_000)
        } else {
            previewFiles[
                i %
                    previewFiles.size,
            ]
        }
        SampleFile(i, f.copy(uri = "${f.uri}#$i"), i < read, if (i < read) null else "Waiting to read")
    }

    private val chosen = SetupInputs(SetupMark.ACCESS_CHOSEN, planned = 71, accessGiven = true)
    private fun sample(read: Int, pending: Int = 71 - read, pause: String? = null) =
        SetupUi(setup = chosen, access = Access.ALL, taste = progress(read, pending), sample = planFiles(read), pauseReason = pause)

    private val start = sample(0)

    /** Access chosen, the plan still syncing the library: nothing is counted and no file has arrived. */
    private val planning = SetupUi(setup = chosen.copy(planned = 0), access = Access.ALL, taste = TasteProgress(emptyMap(), emptySet(), 0, null, false))
    private val readingFew = sample(30)
    private val paused = sample(30, pause = "Waiting to charge")
    private val readFew = sample(66, pending = 0)

    @Test fun sampleStart() {
        setup("setup_sample_start_light", start)
        rule.onNodeWithText(Words.SAMPLE_PICKING).assertIsDisplayed()
        rule.onNodeWithText(Words.TRY_SEARCH).assertIsNotEnabled()
        rule.onNodeWithText(Words.GO_TO_HOME).assertIsDisplayed()
        // No time before it is measured.
        rule.onAllNodesWithText("left.", substring = true).assertCountEquals(0)
    }

    @Test fun samplePlanningIsTheStart() {
        setup("setup_sample_planning_light", planning)
        rule.onNodeWithText(Words.SAMPLE_PICKING).assertIsDisplayed()
        rule.onAllNodesWithText(Words.readOf(0, 0)).assertCountEquals(0)
        rule.onAllNodesWithText(Words.seeAll(0, 0)).assertCountEquals(0)
        // The pile says where things stand but opens nothing yet.
        rule.onNodeWithContentDescription(Words.SAMPLE_PICKING).assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    /** A cold relaunch mid-sample: progress arrives before the files, and the files read by then land without settling. */
    @Test fun theFirstDeliveryNeverReplaysThePile() {
        val ui = mutableStateOf(readingFew.copy(sample = null))
        rule.mainClock.autoAdvance = false
        rule.setContent { ShotTheme(false) { SetupContent(ui.value, reduceMotion = false) } }
        rule.mainClock.advanceTimeByFrame()
        ui.value = readingFew
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        fun top() = rule.onNodeWithTag("card:29", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val first = top()
        rule.mainClock.advanceTimeBy(2_000)
        assertEquals(top(), first)
    }

    @Test fun sampleStartBigText() = setup("setup_sample_start_text2", start, fontScale = 2f)

    @Test fun sampleReading() {
        setup("clarity_sample_light", readingFew)
        rule.onAllNodesWithText(Words.readCount(30)).assertCountEquals(1)
        rule.onNodeWithText("About a minute left. Ready to try.").assertExists()
        rule.onNodeWithText(Words.TRY_SEARCH).assertIsEnabled()
        rule.onAllNodesWithText("/", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("Up to date").assertCountEquals(0)
    }

    /** A 6.3" phone at default text: the pile and both footer buttons fit with no scroll. */
    @Test fun sampleFitsWithoutScrolling() {
        setup("setup_sample_fit_light", readingFew)
        val pile = rule.onNodeWithContentDescription(Words.readCount(30)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val tryButton = rule.onNodeWithText(Words.TRY_SEARCH).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        rule.onNodeWithText(Words.GO_TO_HOME).assertIsDisplayed()
        assertTrue("$pile above $tryButton", pile.bottom <= tryButton.top)
    }

    @Test fun sampleReadingDark() = setup("clarity_sample_dark", readingFew, dark = true)

    @Test fun sampleReadingBigText() = setup("setup_sample_reading_text2", readingFew, fontScale = 2f)

    @Test fun samplePaused() {
        setup("setup_sample_paused_light", paused)
        rule.onNodeWithText("Waiting to charge").assertExists()
        rule.onAllNodesWithText("left.", substring = true).assertCountEquals(0)
        rule.onNodeWithText(Words.readCount(30)).assertExists()
    }

    @Test fun samplePausedBigText() = setup("setup_sample_paused_text2", paused, fontScale = 2f)

    @Test fun sampleDone() {
        var tried = false
        setup("setup_sample_done_light", readFew, actions = SetupActions(tryNow = { tried = true }))
        // Five Name only files: the label counts what was read, the bar what is done.
        rule.onNodeWithText(Words.readOf(66, 71)).assertExists()
        rule.onNodeWithText(Words.SAMPLE_DONE).assertExists()
        rule.onAllNodesWithText("left.", substring = true).assertCountEquals(0)
        rule.onNodeWithText(Words.TRY_SEARCH).assertIsDisplayed().performClick()
        assertEquals(true, tried)
    }

    @Test fun sampleDoneDark() = setup("setup_sample_done_dark", readFew, dark = true)

    @Test fun sampleDoneBigText() = setup("setup_sample_done_text2", readFew, fontScale = 2f)

    @Test fun seeAllOpensFromThePileAndTheButton() {
        var opened = 0
        setup("setup_sample_see_all", readingFew, actions = SetupActions(seeAll = { opened++ }))
        rule.onNodeWithContentDescription(Words.readCount(30)).performClick()
        // No total while the plan can still shrink.
        rule.onNodeWithText(Words.seeAll(71, pending = 41)).performScrollTo().performClick()
        assertEquals(2, opened)
    }

    @Test fun theMascotWalksOnlyWhileTheSampleReads() {
        assertEquals(MascotMood.WALKING, start.mood())
        assertEquals(MascotMood.WALKING, planning.mood())
        assertEquals(MascotMood.WALKING, readingFew.mood())
        assertEquals(MascotMood.RESTING, paused.mood())
        assertEquals(MascotMood.RESTING, readFew.mood())
        assertEquals(MascotMood.WELCOME, welcome.mood())
    }

    /** Before core's first progress (picking): the title, the pile's placeholders, the count and the note, never blank. */
    @Test fun theSampleNeverStartsBlank() {
        setup("setup_sample_no_progress_light", planning.copy(taste = null))
        rule.onNodeWithText(Words.SAMPLE_TITLE).assertIsDisplayed()
        rule.onNodeWithText(Words.SAMPLE_PICKING).assertIsDisplayed()
        rule.onNodeWithText(Words.sampleNote(reading = true, readyToTry = false, secondsLeft = null)!!).assertIsDisplayed()
    }

    private fun centreX(text: String) = rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.center.x

    private fun top(text: String) = rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    private fun bottom(text: String) = rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.bottom

    /** Centred on the pile's axis, and every row in place from the first frame: picking, reading and done stand alike. */
    @Test fun theSampleIsCentredAndNeverShifts() {
        val ui = mutableStateOf(planning)
        rule.setContent { ShotTheme(false) { SetupContent(ui.value, reduceMotion = true) } }
        val titleTop = top(Words.SAMPLE_TITLE)
        val countBottom = bottom(Words.SAMPLE_PICKING)
        for (state in listOf(start, readingFew, readFew)) {
            ui.value = state
            rule.waitForIdle()
            assertEquals(titleTop, top(Words.SAMPLE_TITLE))
            val read = state.taste!!.read
            val pending = state.taste.pending.toLong()
            val count = if (read > 0) Words.readOf(read.toLong(), 71, pending) else Words.SAMPLE_PICKING
            // The count sits on the bar whether it is the long picking sentence or a short count.
            assertEquals(countBottom, bottom(count))
            val pile = rule.onNodeWithContentDescription(count).fetchSemanticsNode().boundsInRoot.center.x
            val dp = rule.density.density
            for (text in listOf(Words.SAMPLE_TITLE, count, Words.seeAll(71, pending))) {
                assertEquals("$text centred", pile / dp, centreX(text) / dp, 1f)
            }
        }
    }

    /** Spare height is split above and below the group, not left as a band under it. */
    @Test fun spareHeightIsSplitAboveAndBelow() {
        RuntimeEnvironment.setQualifiers("+h1500dp")
        val height = mutableStateOf(1_000.dp)
        rule.setContent { ShotTheme(false) { Box(Modifier.height(height.value)) { SetupContent(readingFew, reduceMotion = true) } } }
        val before = top(Words.SAMPLE_TITLE)
        height.value = 1_200.dp
        rule.waitForIdle()
        assertEquals(100f, (top(Words.SAMPLE_TITLE) - before) / rule.density.density, 2f)
    }

    /** A pile narrower than its full size keeps its shape: each card's offset shrinks with the cards, as on iOS. */
    @Test fun pileSlotsShrinkWithThePile() {
        val cards = planFiles(4).take(4)
        rule.setContent { ShotTheme(false) { Box(Modifier.width(112.dp)) { SamplePile(cards, emptyList(), true, "pile", "see", null) } } }
        fun centre(i: Int) = rule.onNodeWithTag("card:$i", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
        val scale = 112f / SamplePile.FOOTPRINT_DP
        val dp = rule.density.density
        assertEquals(SamplePile.dx(3) * scale, (centre(3).x - centre(0).x) / dp, 0.5f)
        assertEquals(SamplePile.dy(3) * scale, (centre(3).y - centre(0).y) / dp, 0.5f)
    }

    // --- Try it and the commit choice ---

    private val tryUi = readFew.copy(setup = chosen.copy(mark = SetupMark.TRYING))
    private val scoped = SearchUi(setUp = true, scope = (1L..79L).toList(), unread = 31, sampleRead = 48, photosRead = 12)
    private fun file(name: String, kind: Kind) = SourceFile("demo:$name", name, "Pictures", "image/png", kind, 120_000, 1_760_000_000_000)

    @Test fun tryWithoutSuggestionsLight() {
        shot("setup_tryit_empty_light") { TryItContent(tryUi, scoped, SearchActions(), SetupActions()) }
        rule.onNodeWithText(Words.TRY_TITLE).assertExists()
        rule.onNodeWithText(Words.searchingTry(tryUi.taste!!.read.toLong())).assertExists()
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

    /** The suggestions (or the line without them) land in a row held from the first frame: nothing below moves. */
    @Test fun trySuggestionsArriveWithoutMovingThePage() {
        val terms = mutableStateOf<List<String>?>(null)
        rule.setContent { ShotTheme(false) { TryItContent(tryUi, scoped, SearchActions(), SetupActions(), suggestions = terms.value) } }
        val before = top(Words.FILES_TO_TRY)
        rule.onAllNodesWithText(Words.TRY_WITHOUT_SUGGESTIONS).assertCountEquals(0)
        terms.value = listOf("invoice", "deposit", "lease")
        rule.waitForIdle()
        rule.onNodeWithText("invoice").assertExists()
        assertEquals(before, top(Words.FILES_TO_TRY))
    }

    @Test fun tryWithoutSuggestionsHoldsTheSameRow() {
        val terms = mutableStateOf<List<String>?>(null)
        rule.setContent { ShotTheme(false) { TryItContent(tryUi, scoped, SearchActions(), SetupActions(), suggestions = terms.value) } }
        val before = top(Words.FILES_TO_TRY)
        terms.value = emptyList()
        rule.waitForIdle()
        rule.onNodeWithText(Words.TRY_WITHOUT_SUGGESTIONS).assertExists()
        assertEquals(before, top(Words.FILES_TO_TRY))
    }

    /** The phone's bug: the scope line waited for the search's scope and landed a moment late, pushing the grid down. */
    @Test fun tryScopeLineIsThereOnTheFirstFrame() {
        val search = mutableStateOf(scoped.copy(scope = null))
        rule.setContent { ShotTheme(false) { TryItContent(tryUi, search.value, SearchActions(), SetupActions()) } }
        rule.onNodeWithText(Words.searchingTry(tryUi.taste!!.read.toLong())).assertExists()
        val before = top(Words.FILES_TO_TRY)
        search.value = scoped
        rule.waitForIdle()
        assertEquals(before, top(Words.FILES_TO_TRY))
    }

    /** One body at a time, as on iOS: the line without suggestions replaces the body, not joins it. */
    @Test fun tryShowsOneBodyAtATime() {
        val terms = mutableStateOf<List<String>?>(listOf("invoice"))
        rule.setContent { ShotTheme(false) { TryItContent(tryUi, scoped, SearchActions(), SetupActions(), suggestions = terms.value) } }
        rule.onNodeWithText(Words.TRY_BODY).assertExists()
        rule.onAllNodesWithText(Words.TRY_WITHOUT_SUGGESTIONS).assertCountEquals(0)
        terms.value = emptyList()
        rule.waitForIdle()
        rule.onNodeWithText(Words.TRY_WITHOUT_SUGGESTIONS).assertExists()
        rule.onAllNodesWithText(Words.TRY_BODY).assertCountEquals(0)
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
        rule.onNodeWithText(Words.searchingTry(48)).assertExists()
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
        shot("clarity_choose_light") { SetupContent(commitUi) }
        rule.onNodeWithText(Words.CHOOSE_TITLE).assertExists()
        rule.onNodeWithText(Words.CHOOSE_PACE).assertExists()
        // No hours, nights, sessions or schedules.
        listOf("hour", "night", "session", "schedule", "library").forEach { rule.onAllNodesWithText(it, substring = true).assertCountEquals(0) }
        // The count above says what is waiting; where it comes from was never true of whole-phone access.
        rule.onAllNodesWithText("From your selected", substring = true).assertCountEquals(0)
    }

    @Test fun commitDark() = shot("clarity_choose_dark", dark = true) { SetupContent(commitUi) }

    @Test fun commitBigText() = shot("setup_commit_text1_5", fontScale = 1.5f) { SetupContent(commitUi) }

    @Test fun threeButtonsCallTheirActions() {
        val calls = mutableListOf<String>()
        val actions = SetupActions(commit = { calls += "commit($it)" }, decideLater = { calls += "later" })
        shot("setup_commit_buttons") { SetupContent(commitUi, actions = actions) }
        rule.onNodeWithText("While charging").performClick()
        assertEquals(emptyList<String>(), calls)
        rule.onNodeWithText(Words.DONE).performClick()
        rule.onNodeWithText(Words.READ_15_NOW).performClick()
        rule.onNodeWithText("Start reading").performClick()
        rule.onNodeWithText(Words.NOT_NOW).performClick()
        rule.onNodeWithText(Words.DONE).performClick()
        assertEquals(listOf("commit(true)", "commit(false)", "later"), calls)
    }

    @Test fun continueAsksForTheCommitChoice() {
        var continued = false
        shot("setup_tryit_continue") { TryItContent(tryUi, scoped, SearchActions(), SetupActions(continueToCommit = { continued = true })) }
        rule.onNodeWithText(Words.CHOOSE_WHEN).performScrollTo().performClick()
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
        rule.onNodeWithText("Reading your files · 29% read").assertExists()
        // Reading now keeps the file's name beside a small tile.
        rule.onNodeWithText(previewFiles.first().name).assertExists()
        rule.onNodeWithText("Pause").assertExists()
        rule.onNodeWithText("Details").assertExists()
        rule.onNodeWithText(Words.notReadYet(8_118)).assertExists()
    }

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
