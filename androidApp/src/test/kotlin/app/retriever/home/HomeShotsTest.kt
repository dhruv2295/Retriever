package app.retriever.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.retriever.ShotTheme
import app.retriever.core.DeviceWords
import app.retriever.core.FileTotals
import app.retriever.core.HomeFacts
import app.retriever.core.IndexState
import app.retriever.core.Kind
import app.retriever.core.ReadingPolicy
import app.retriever.core.ReadingProgress
import app.retriever.core.SourceFile
import app.retriever.core.StopReason
import app.retriever.core.UpgradeStage
import app.retriever.core.Words
import app.retriever.core.YearMemory
import app.retriever.model.ModelState
import app.retriever.model.UpgradeActions
import app.retriever.model.UpgradeUi
import app.retriever.nav.BrowseRoute
import app.retriever.nav.DuplicatesRoute
import app.retriever.nav.FoldersRoute
import app.retriever.nav.Route
import app.retriever.nav.SettingsRoute
import app.retriever.nav.StatusRoute
import app.retriever.search.SearchActions
import app.retriever.search.SearchContent
import app.retriever.search.SearchUi
import app.retriever.shot
import app.retriever.tallWindowForBigText
import app.retriever.ui.Format
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
import java.time.LocalDate
import java.time.ZoneId

/** Renders Home's states, light and dark, to androidApp/build/shots/clarity_home_*.png, and checks what each one does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeShotsTest {
    @get:Rule val rule = createComposeRule()

    // Tall, so the lazy grid composes the whole page and a shot shows all of it.
    @Before fun tallWindow() = RuntimeEnvironment.setQualifiers("+h2000dp")

    private val now = System.currentTimeMillis()

    private fun file(name: String, kind: Kind, folder: String, days: Int, size: Long) =
        SourceFile("demo:$name", name, folder, "application/octet-stream", kind, size, now - days * 86_400_000L)

    private val counts = mapOf(
        Kind.SCREENSHOT to 96L,
        Kind.PDF to 300L,
        Kind.DOC to 112L,
        Kind.PHOTO to 2_140L,
        Kind.VIDEO to 310L,
        Kind.AUDIO to 55L,
        Kind.OTHER to 372L,
    )

    /** The newest [n] pictures, every fourth a video. */
    private fun media(n: Int) = (1..n).map {
        val taken = now - it * 86_400_000L
        SourceFile("demo:p$it", "p$it", "Camera", "image/jpeg", if (it % 4 == 0) Kind.VIDEO else Kind.PHOTO, 3_000_000, taken, takenAt = taken)
    }

    private val facts = HomeFacts(
        newMedia = media(6),
        starred = listOf(
            file("Passport.jpg", Kind.PHOTO, "Pictures", 3, 2_000_000),
            file("Lease_Agreement_2026.pdf", Kind.PDF, "Download", 0, 2_100_000),
            file("Voice note.m4a", Kind.AUDIO, "Recordings", 20, 300_000),
        ),
        starredCount = 3,
        newDocs = listOf(file("Electricity bill October.pdf", Kind.PDF, "Download", 0, 340_000)),
        newShots = listOf(file("Screenshot_1.png", Kind.SCREENSHOT, "Screenshots", 2, 400_000)),
        folders = 148,
        largeCount = 212,
        largeBytes = 41_000_000_000,
        possibleCopies = 38,
        kindTotals = counts.mapValues { (_, n) -> FileTotals(n, n * 1_000_000, 0) },
    )

    private val day = LocalDate.of(2026, 10, 17)

    private fun memory(year: Int, photos: Long, videos: Long) =
        YearMemory(year, photos, videos, SourceFile("demo:m$year", "m$year", "Camera", "image/jpeg", Kind.PHOTO, 1_000_000, 1L, takenAt = 1L))

    private val memories = listOf(memory(2023, 8, 0), memory(2021, 0, 1), memory(2019, 3, 2))

    private val reading = ReadingProgress(read = 1_200, scheduled = 2_100, nameOnly = 372)
    private val afterDemo = ReadingProgress(read = 28, scheduled = 25_763, nameOnly = 2)

    private fun line(p: ReadingProgress = reading, stop: StopReason? = null, on: Boolean = stop == null, minutes: Int = 0, pluggedIn: Boolean = true) =
        ReadingPolicy.decide(p, stop, on, on, DeviceWords.ANDROID, minutes, pluggedIn)

    private val base = HomeUi(
        libraryChosen = true,
        counts = counts,
        contentRead = 1_200,
        waiting = 2_100,
        nameOnly = 372,
        home = facts,
        starred = 3,
        memories = memories,
        memoriesDay = day,
        readingStatus = line(),
    )

    private val downloading = UpgradeUi(UpgradeStage.DOWNLOAD, ModelState.Downloading(0.4f, false), sizeBytes = 387_710_976)

    /** Every state the status line has on Home, by the name its shot carries. */
    private val states: Map<String, Pair<HomeUi, UpgradeUi>> = mapOf(
        "default" to (base to UpgradeUi()),
        "first_day" to (
            base.copy(
                contentRead = 0,
                waiting = 0,
                rest = 3_300,
                trial = true,
                readingStatus = line(ReadingProgress(notScheduled = 3_300, nameOnly = 372, sample = true), on = false),
            ) to
                UpgradeUi()
            ),
        // The demo's 30 files carried over to the whole phone: 30 of 25,793, a bar that has only just begun.
        "charging" to (base.copy(readingStatus = line(afterDemo)) to UpgradeUi()),
        "unplugged_demo" to (base.copy(readingStatus = line(afterDemo, stop = StopReason.UNPLUGGED, pluggedIn = false)) to UpgradeUi()),
        "not_now" to (
            base.copy(
                contentRead = 28,
                nameOnly = 2,
                waiting = 0,
                rest = 25_763,
                trial = true,
                notNow = true,
                readingStatus = line(afterDemo.copy(scheduled = 0, notScheduled = 25_763, sample = true, notNow = true), on = false),
            ) to
                UpgradeUi()
            ),
        "counting" to (base.copy(readingStatus = line(afterDemo.copy(counted = false))) to UpgradeUi()),
        "unplugged" to (base.copy(readingStatus = line(stop = StopReason.UNPLUGGED, pluggedIn = false)) to UpgradeUi()),
        "charging_up" to (base.copy(readingStatus = line(stop = StopReason.CHARGING_UP)) to UpgradeUi()),
        "read_now" to (base.copy(readingStatus = line(minutes = 12, pluggedIn = false)) to UpgradeUi()),
        "downloading" to (base to downloading),
        "meaning" to (base.copy(readingStatus = line(ReadingProgress(read = 3_300, meaningWaiting = 1_100, nameOnly = 372))) to UpgradeUi()),
        "all_read" to
            (base.copy(contentRead = 3_300, waiting = 0, readingStatus = line(ReadingProgress(read = 3_300, nameOnly = 372), on = false)) to UpgradeUi()),
        "no_access" to (base.copy(hasAccess = false) to UpgradeUi()),
    )

    @Composable
    private fun Screen(
        home: HomeUi,
        index: IndexState = IndexState(),
        actions: SearchActions = SearchActions(),
        homeActions: HomeActions = HomeActions(),
        celebration: Celebration = Celebration(),
        upgrade: UpgradeUi = UpgradeUi(),
        upgradeActions: UpgradeActions = UpgradeActions(),
        offer: Boolean = false,
    ) = SearchContent(SearchUi(), home, actions) { padding, state, side, _ ->
        HomeBody(home, index.running, actions, state, padding, side, homeActions, celebration, upgrade, upgradeActions, offer)
    }

    private fun shot(state: String, dark: Boolean, fontScale: Float = 1f, prefix: String = "clarity_home") {
        val (ui, upgrade) = states.getValue(state)
        tallWindowForBigText(fontScale)
        rule.setContent { ShotTheme(dark, fontScale) { Screen(ui, upgrade = upgrade) } }
        rule.waitForIdle()
        rule.shot(
            "${prefix}_${state}_" + if (fontScale > 1f) {
                "text2"
            } else if (dark) {
                "dark"
            } else {
                "light"
            },
        )
    }

    private fun described(prefix: String) = SemanticsMatcher("content description starts with $prefix") {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d.startsWith(prefix) } == true
    }

    private fun top(text: String) = rule.onAllNodesWithText(text).onFirst().fetchSemanticsNode().boundsInRoot.top

    @Test fun defaultLight() {
        shot("default", false)
        rule.onNodeWithText("Processing while you charge").assertExists()
        // A live count by the one rule (name-only files count), not a percent that sits still for minutes.
        rule.onNodeWithText(Words.readOf(1_572, 3_672)).assertExists()
        rule.onNodeWithText("You can close Retriever; it carries on. Search works the whole time.").assertExists()
        rule.onAllNodesWithText("%", substring = true).assertCountEquals(0)
        // Home is a starting place: no report, no Tidy up.
        rule.onNodeWithText("Tidy up").assertDoesNotExist()
        rule.onNodeWithText(Words.UPGRADE_TITLE).assertDoesNotExist()
    }

    @Test fun defaultDark() = shot("default", true)

    @Test fun defaultLargestText() {
        shot("default", false, fontScale = 2f)
        // Large text wraps the line, never cuts it.
        rule.onNodeWithText("Processing while you charge").assertExists()
        rule.onNodeWithContentDescription("Photos, 2,140").assertExists()
        // Two Browse tiles a row: the second sits beside the first, not under it.
        fun tileTop(prefix: String) = rule.onNode(described(prefix)).fetchSemanticsNode().boundsInRoot.top
        assertEquals(tileTop("Photos, "), tileTop("Videos, "), 0.5f)
        // The placeholder wraps rather than cutting the question short.
        rule.onNodeWithText(Words.SEARCH_FIELD).assertExists()
    }

    @Test fun firstDayLight() {
        shot("first_day", false)
        rule.onNodeWithText("Only names can be found for now").assertExists()
        rule.onNodeWithText(Words.readOf(372, 3_672)).assertExists()
        rule.onNodeWithText(Words.CHOOSE_WHEN).assertExists()
    }

    @Test fun firstDayDark() = shot("first_day", true)

    @Test fun unpluggedLight() {
        shot("unplugged", false)
        rule.onNodeWithText("Waits until you charge").assertExists()
        rule.onNodeWithText(Words.readOf(1_572, 3_672)).assertExists()
        rule.onNodeWithText("Retriever carries on when your phone is charging.").assertExists()
        rule.onNodeWithText(Words.READ_FOR_15).assertExists()
    }

    @Test fun unpluggedDark() = shot("unplugged", true)

    @Test fun chargingUpLight() {
        shot("charging_up", false)
        rule.onNodeWithText("Charging up first").assertExists()
        rule.onNodeWithText("Processing starts once your battery has a bit more charge.").assertExists()
    }

    @Test fun chargingUpDark() = shot("charging_up", true)

    @Test fun readNowLight() {
        shot("read_now", false)
        rule.onNodeWithText("Processing for 15 minutes · 12 min left").assertExists()
        rule.onNodeWithText("Stop").assertExists()
    }

    @Test fun readNowDark() = shot("read_now", true)

    @Test fun downloadingLight() {
        shot("downloading", false)
        rule.onNodeWithText("Downloading search by meaning · 40%").assertExists()
        // The line is the download: no card for it as well.
        rule.onNodeWithText(Words.UPGRADE_TITLE).assertDoesNotExist()
    }

    @Test fun downloadingDark() = shot("downloading", true)

    @Test fun meaningLight() {
        shot("meaning", false)
        rule.onNodeWithText("Adding search by meaning").assertExists()
        rule.onNodeWithText(Words.meaningOf(2_200, 3_300)).assertExists()
    }

    @Test fun meaningDark() = shot("meaning", true)

    @Test fun allReadLight() {
        shot("all_read", false)
        rule.onNodeWithText("All done. Retriever has processed everything it can").assertExists()
        rule.onNodeWithContentDescription("Dismiss").assertExists()
    }

    @Test fun allReadDark() = shot("all_read", true)

    @Test fun noAccessLight() {
        shot("no_access", false)
        rule.onNodeWithText("Retriever can’t see your files").assertExists()
        rule.onNodeWithText("Allow access").assertExists()
        // Pictures cannot load without access: no Recently added, no On this day.
        rule.onNodeWithText(Words.RECENTLY_ADDED).assertDoesNotExist()
        rule.onNodeWithText("On this day").assertDoesNotExist()
    }

    @Test fun noAccessDark() = shot("no_access", true)

    @Test fun theOrderIsTitleStatusStarredRecentOnThisDayBrowse() {
        rule.setContent { ShotTheme(false) { Screen(base) } }
        val order =
            listOf("Retriever", "Processing while you charge", "Starred", Words.RECENTLY_ADDED, "On this day", Words.MORE_ON_PHONE)
                .map(::top)
        assertEquals(order.sorted(), order)
        rule.onNode(hasText("Retriever") and isHeading()).assertExists()
    }

    @Test fun onlyTheStateIsAnnouncedNotEachPercent() {
        rule.setContent { ShotTheme(false) { Screen(base) } }
        rule.onNode(hasContentDescription("Processing while you charge") and SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertExists()
    }

    @Test fun theLineOpensReadingStatus() {
        val went = mutableListOf<Route>()
        rule.setContent { ShotTheme(false) { Screen(base, actions = SearchActions(onNavigate = { went += it })) } }
        rule.onNodeWithText("Processing while you charge").performClick()
        assertEquals(listOf<Route>(StatusRoute), went)
    }

    @Test fun eachButtonDoesWhatItSays() {
        val did = mutableListOf<String>()
        var ui by mutableStateOf(states.getValue("unplugged").first)
        var upgrade by mutableStateOf(UpgradeUi())
        rule.setContent {
            ShotTheme(false) {
                Screen(
                    ui,
                    homeActions = HomeActions(readNow = { did += "read" }, stopReadNow = { did += "stop" }, resume = { did += "resume" }),
                    upgrade = upgrade,
                    upgradeActions = UpgradeActions(cancel = { did += "cancel" }),
                )
            }
        }
        rule.onNodeWithText(Words.READ_FOR_15).performClick()
        ui = states.getValue("read_now").first
        rule.onNodeWithText("Stop").performClick()
        ui = base.copy(paused = true, readingStatus = line(stop = StopReason.PAUSED))
        rule.onNodeWithText("Resume").performClick()
        ui = base
        upgrade = downloading
        rule.onNodeWithText("Stop").performClick()
        assertEquals(listOf("read", "stop", "resume", "cancel"), did)
    }

    @Test fun chooseWhenToReadOpensTheThreeAnswersAsASheet() {
        val chosen = mutableListOf<Boolean>()
        rule.setContent { ShotTheme(false) { Screen(states.getValue("not_now").first, homeActions = HomeActions(commit = { chosen += it })) } }
        rule.onNodeWithText(Words.CHOOSE_WHEN).performClick()
        val title = Words.chooseTitle(DeviceWords.ANDROID)
        rule.onNodeWithText(title).assertExists()
        // Home's sheet has no demo line, and the count is the whole phone's rest.
        rule.onAllNodesWithText("Demo", substring = true).assertCountEquals(0)
        rule.onNodeWithText(Words.chooseBody(25_763)).assertExists()
        rule.onNodeWithText(Words.READ_15_NOW).assertExists()
        rule.onNodeWithText(Words.START_PROCESSING).performClick()
        rule.waitForIdle()
        assertEquals(listOf(true), chosen)
        rule.onNodeWithText(title).assertDoesNotExist()
    }

    // The demo flow's Home renders: the count and a bar exactly done / total on every state, none while counting.

    @Test fun demoChargingLight() {
        shot("charging", false, prefix = "demo_home")
        rule.onNodeWithText("Processing while you charge").assertExists()
        rule.onNodeWithText(Words.readOf(30, 25_793)).assertExists()
        assertEquals(30L to 25_793L, states.getValue("charging").first.readingStatus!!.let { it.done to it.total })
    }

    @Test fun demoChargingDark() = shot("charging", true, prefix = "demo_home")

    @Test fun demoChargingLargestText() = shot("charging", false, fontScale = 2f, prefix = "demo_home")

    @Test fun demoUnpluggedLight() {
        shot("unplugged_demo", false, prefix = "demo_home")
        rule.onNodeWithText("Waits until you charge").assertExists()
        rule.onNodeWithText(Words.readOf(30, 25_793)).assertExists()
    }

    @Test fun demoUnpluggedDark() = shot("unplugged_demo", true, prefix = "demo_home")

    @Test fun demoNotNowLight() {
        shot("not_now", false, prefix = "demo_home")
        rule.onNodeWithText("Only names can be found for now").assertExists()
        rule.onNodeWithText(Words.readOf(30, 25_793)).assertExists()
        rule.onNodeWithText(Words.CHOOSE_WHEN).assertExists()
    }

    @Test fun demoNotNowDark() = shot("not_now", true, prefix = "demo_home")

    @Test fun demoCountingLight() {
        shot("counting", false, prefix = "demo_home")
        rule.onNodeWithText("Counting your files…").assertExists()
        rule.onAllNodesWithText("files processed", substring = true).assertCountEquals(0)
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
    }

    @Test fun demoCountingDark() = shot("counting", true, prefix = "demo_home")

    @Test fun dismissingAllDoneHidesTheLine() {
        var dismissed = 0
        var ui by mutableStateOf(states.getValue("all_read").first)
        rule.setContent { ShotTheme(false) { Screen(ui, homeActions = HomeActions(dismissCaughtUp = { dismissed++ })) } }
        rule.onNodeWithContentDescription("Dismiss").performClick()
        assertEquals(1, dismissed)
        ui = ui.copy(caughtUpDismissed = true)
        rule.onNodeWithText("All done", substring = true).assertDoesNotExist()
    }

    @Test fun theDogPopsOnceWhenAllIsRead() {
        var done = 0
        rule.setContent { ShotTheme(false) { Screen(states.getValue("all_read").first, celebration = Celebration(due = { true }, done = { done++ })) } }
        // The haptic and the mark come 0.2 s after the dog appears, not before.
        rule.mainClock.advanceTimeBy(100)
        assertEquals(0, done)
        rule.mainClock.advanceTimeBy(300)
        rule.waitForIdle()
        assertEquals(1, done)
    }

    /** Home's bottom, in a window tall enough for the whole page, so Free up space is in the shot. */
    private fun tools(name: String, dark: Boolean, fontScale: Float = 1f) {
        RuntimeEnvironment.setQualifiers(if (fontScale > 1f) "+h5200dp" else "+h3000dp")
        rule.setContent { ShotTheme(dark, fontScale) { Screen(base) } }
        rule.waitForIdle()
        rule.onNodeWithText(Words.FREE_UP_SPACE).assertIsDisplayed()
        rule.shot(name)
    }

    @Test fun toolsLight() = tools("home_tools_light", dark = false)

    @Test fun toolsDark() = tools("home_tools_dark", dark = true)

    @Test fun toolsLargestText() = tools("home_tools_text2_0", dark = false, fontScale = 2f)

    @Test fun freeUpSpaceIsLastAndOpensLargeFilesAndDuplicates() {
        val went = mutableListOf<Route>()
        RuntimeEnvironment.setQualifiers("+h3000dp")
        rule.setContent { ShotTheme(false) { Screen(base, actions = SearchActions(onNavigate = { went += it })) } }
        assertTrue(top(Words.FREE_UP_SPACE) > top(Words.MORE_ON_PHONE))
        rule.onNodeWithText(Words.largeFilesDetail(212, Format.bytes(41_000_000_000))).performClick()
        rule.onNodeWithText(Words.possibleCopies(38)).performClick()
        rule.onNodeWithText(Words.NEVER_DELETES).assertExists()
        assertEquals(listOf(BrowseRoute(sort = "LARGEST", title = Words.LARGE_FILES), DuplicatesRoute), went)
    }

    @Test fun freeUpSpaceHidesARowWithNothingAndTheSectionWhenBothAreEmpty() {
        var home by mutableStateOf(facts.copy(possibleCopies = 0))
        RuntimeEnvironment.setQualifiers("+h3000dp")
        rule.setContent { ShotTheme(false) { Screen(base.copy(home = home)) } }
        rule.onNodeWithText(Words.LARGE_FILES).assertExists()
        rule.onNodeWithText(Words.FIND_DUPLICATES).assertDoesNotExist()
        home = home.copy(largeCount = 0, largeBytes = 0)
        rule.onNodeWithText(Words.FREE_UP_SPACE).assertDoesNotExist()
        rule.onNodeWithText(Words.NEVER_DELETES).assertDoesNotExist()
    }

    @Test fun theOptionalDownloadFollowsBrowseAndCanBeDismissed() {
        var dismissed = 0
        val offer = UpgradeUi(UpgradeStage.OFFER, sizeBytes = 387_710_976)
        rule.setContent { ShotTheme(false) { Screen(base, upgrade = offer, upgradeActions = UpgradeActions(notNow = { dismissed++ }), offer = true) } }
        rule.waitForIdle()
        rule.shot("clarity_home_optional_light")
        assertTrue(top(Words.OPTIONAL) > top(Words.MORE_ON_PHONE))
        rule.onNodeWithText(Words.NOT_NOW).performClick()
        assertEquals(1, dismissed)
    }

    @Test fun recentlyAddedMergesTheNewestAndOpensThem() {
        val opened = mutableListOf<SourceFile>()
        val went = mutableListOf<Route>()
        rule.setContent { ShotTheme(false) { Screen(base, actions = SearchActions(onOpenFile = { opened += it }, onNavigate = { went += it })) } }
        rule.onNode(described("Electricity bill October.pdf")).performClick()
        rule.onNode(hasContentDescription(Words.RECENTLY_ADDED) and isHeading()).performClick()
        assertEquals(listOf(facts.newDocs[0]), opened)
        assertEquals(listOf<Route>(BrowseRoute(title = Words.RECENTLY_ADDED)), went)
    }

    @Test fun browseListsKindsWithCountsThenFolders() {
        val went = mutableListOf<Route>()
        rule.setContent { ShotTheme(false) { Screen(base, actions = SearchActions(onNavigate = { went += it })) } }
        rule.onNodeWithContentDescription("Photos, 2,140").assertExists()
        // Documents and PDFs are one tile.
        rule.onNodeWithContentDescription("Documents, 412").performClick()
        rule.onNodeWithContentDescription("Folders, 148").performClick()
        rule.onNodeWithText("Other files").assertDoesNotExist()
        assertEquals(listOf(BrowseRoute(kind = "PDF", title = "Documents"), FoldersRoute), went)
    }

    @Test fun aKindWithNoFilesIsLeftOut() {
        rule.setContent { ShotTheme(false) { Screen(base.copy(home = facts.copy(kindTotals = facts.kindTotals - Kind.AUDIO))) } }
        rule.onNodeWithContentDescription("Audio, 55").assertDoesNotExist()
        rule.onNodeWithContentDescription("Photos, 2,140").assertExists()
    }

    @Test fun starredOpensAFileAndItsList() {
        val opened = mutableListOf<SourceFile>()
        val went = mutableListOf<Route>()
        rule.setContent { ShotTheme(false) { Screen(base, actions = SearchActions(onOpenFile = { opened += it }, onNavigate = { went += it })) } }
        rule.onNodeWithContentDescription("Audio, Voice note.m4a, starred").performClick()
        rule.onNode(hasContentDescription("Starred") and isHeading()).performClick()
        assertEquals(listOf(facts.starred[2]), opened)
        assertEquals(listOf<Route>(BrowseRoute(title = "Starred", starredOnly = true)), went)
    }

    @Test fun onThisDayOpensItsDayAndHidesOnALongPress() {
        val went = mutableListOf<Route>()
        var hidden = 0
        rule.setContent {
            ShotTheme(false) { Screen(base, actions = SearchActions(onNavigate = { went += it }), homeActions = HomeActions(hideOnThisDay = { hidden++ })) }
        }
        rule.onNode(described("2023, 8 photos from")).performClick()
        val (from, to) = dayRange(LocalDate.of(2023, 10, 17), ZoneId.systemDefault())
        val route = went.single() as BrowseRoute
        assertEquals(from to to, route.takenFrom to route.takenTo)
        rule.onNode(described("2021, 1 video from")).performTouchInput { longClick() }
        assertEquals(1, hidden)
    }

    @Test fun aLongPressOnARecentFileOffersTheFileMenu() {
        rule.setContent { ShotTheme(false) { Screen(base) } }
        rule.onNode(described("Electricity bill October.pdf")).performTouchInput { longClick() }
        rule.onNodeWithText("Share").assertExists()
        rule.onNodeWithText("File info").assertExists()
    }

    @Test fun noSectionDrawsBeforeEverySourceHasAnswered() {
        var ui by mutableStateOf(base.copy(home = null))
        rule.setContent { ShotTheme(false) { Screen(ui) } }
        rule.onNodeWithText(Words.RECENTLY_ADDED).assertDoesNotExist()
        ui = base.copy(memories = emptyList(), memoriesDay = null)
        rule.onNodeWithText(Words.RECENTLY_ADDED).assertDoesNotExist()
        ui = base
        rule.onNodeWithText(Words.RECENTLY_ADDED).assertExists()
    }

    @Test fun firstRunLooksForFiles() {
        rule.setContent { ShotTheme(false) { Screen(HomeUi(libraryChosen = true, counts = emptyMap()), IndexState(running = true)) } }
        rule.onNodeWithText("Looking for your files").assertExists()
    }

    @Test fun theSearchFieldAsksTheQuestionAtTheBottomAndSettingsIsAtTheTop() {
        val went = mutableListOf<Route>()
        rule.setContent { ShotTheme(false) { Screen(base, actions = SearchActions(onNavigate = { went += it })) } }
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val field = rule.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        assertTrue("search in the bottom half", field.top > root.height / 2)
        rule.onNodeWithText(Words.SEARCH_FIELD).assertExists()
        rule.onNodeWithContentDescription("Settings").performClick()
        assertEquals(listOf<Route>(SettingsRoute), went)
    }
}
