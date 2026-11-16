package app.retriever.home

import android.content.Context
import android.os.storage.StorageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
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
import androidx.test.core.app.ApplicationProvider
import app.retriever.ShotTheme
import app.retriever.access.Access
import app.retriever.access.Photos
import app.retriever.access.PickedFolder
import app.retriever.core.DescriptionState
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
import app.retriever.model.ModelRepository
import app.retriever.model.ModelState
import app.retriever.model.UpgradeActions
import app.retriever.model.UpgradeUi
import app.retriever.model.description
import app.retriever.nav.BrowseRoute
import app.retriever.nav.DuplicatesRoute
import app.retriever.nav.FoldersRoute
import app.retriever.nav.Route
import app.retriever.nav.SettingsRoute
import app.retriever.nav.StatusRoute
import app.retriever.search.HomeShell
import app.retriever.search.SearchActions
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
import org.robolectric.Shadows.shadowOf
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
    private val allRead = ReadingProgress(read = 3_300, nameOnly = 372)
    private val firstDay = ReadingProgress(notScheduled = 3_300, nameOnly = 372, sample = true)
    private val notNow = afterDemo.copy(scheduled = 0, notScheduled = 25_763, sample = true, notNow = true)

    private fun line(
        p: ReadingProgress = reading,
        stop: StopReason? = null,
        on: Boolean = stop == null,
        sessionActive: Boolean = false,
        pluggedIn: Boolean = true,
    ) = ReadingPolicy.decide(p, stop, on, on, DeviceWords.ANDROID, sessionActive, pluggedIn)

    private val base = HomeUi(
        libraryChosen = true,
        counts = counts,
        progress = reading,
        home = facts,
        starred = 3,
        memories = memories,
        memoriesDay = day,
        readingStatus = line(),
    )

    /** Photos and two picked folders, not all files: the done card offers the whole phone. */
    private val partialAccess = Access(
        allFiles = false,
        photos = Photos.ALL,
        folders = listOf(PickedFolder("content://tree/Documents", "Documents"), PickedFolder("content://tree/Work", "Work")),
    )

    private val downloading = UpgradeUi(UpgradeStage.DOWNLOAD, ModelState.Downloading(0.4f, false), sizeBytes = 387_710_976)

    /** Every state the status line has on Home, by the name its shot carries. */
    private val states: Map<String, Pair<HomeUi, UpgradeUi>> = mapOf(
        "default" to (base to UpgradeUi()),
        "first_day" to (
            base.copy(
                progress = firstDay,
                readingStatus = line(firstDay, on = false),
            ) to
                UpgradeUi()
            ),
        // The demo's 30 files carried over to the whole phone: 30 of 25,793, a bar that has only just begun.
        "charging" to (base.copy(readingStatus = line(afterDemo)) to UpgradeUi()),
        "unplugged_demo" to (base.copy(readingStatus = line(afterDemo, stop = StopReason.UNPLUGGED, pluggedIn = false)) to UpgradeUi()),
        "not_now" to (
            base.copy(
                progress = notNow,
                readingStatus = line(notNow, on = false),
            ) to
                UpgradeUi()
            ),
        "counting" to (base.copy(readingStatus = line(afterDemo.copy(counted = false))) to UpgradeUi()),
        "unplugged" to (base.copy(readingStatus = line(stop = StopReason.UNPLUGGED, pluggedIn = false)) to UpgradeUi()),
        "charging_up" to (base.copy(readingStatus = line(stop = StopReason.CHARGING_UP)) to UpgradeUi()),
        "processing" to (base.copy(readingStatus = line(sessionActive = true, pluggedIn = false)) to UpgradeUi()),
        "downloading" to (base to downloading),
        "meaning" to (base.copy(readingStatus = line(ReadingProgress(read = 3_300, meaningWaiting = 1_100, nameOnly = 372))) to UpgradeUi()),
        "all_read" to
            (base.copy(progress = allRead, readingStatus = line(allRead, on = false)) to UpgradeUi()),
        "no_access" to (base.copy(hasAccess = false) to UpgradeUi()),
        "widen" to (base.copy(progress = allRead, readingStatus = line(allRead, on = false), access = partialAccess) to UpgradeUi()),
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
    ) = HomeShell(SearchUi(), actions, open = {}) { padding, state ->
        HomeBody(home, index.running, actions, state, padding, homeActions, celebration, upgrade, upgradeActions, offer)
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
        rule.onNodeWithText(Words.strings.learningYourFilesTitle).assertExists()
        // A live count by the one rule (name-only files count), not a percent that sits still for minutes.
        rule.onNodeWithText(Words.readOf(1_572, 3_672)).assertExists()
        rule.onNodeWithText("You can close Retriever; it carries on. Search works the whole time.").assertExists()
        rule.onAllNodesWithText("%", substring = true).assertCountEquals(0)
        // Home is a starting place: no report, no Tidy up.
        rule.onNodeWithText("Tidy up").assertDoesNotExist()
        rule.onNodeWithText(Words.UPGRADE_TITLE).assertDoesNotExist()
    }

    @Test fun defaultLargestText() {
        shot("default", false, fontScale = 2f)
        // Large text wraps the line, never cuts it.
        rule.onNodeWithText(Words.strings.learningYourFilesTitle).assertExists()
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
        rule.onNodeWithText(Words.START_PROCESSING).assertExists()
    }

    @Test fun unpluggedLight() {
        shot("unplugged", false)
        rule.onNodeWithText("Paused · continues when you plug in").assertExists()
        rule.onNodeWithText(Words.readOf(1_572, 3_672)).assertExists()
        rule.onNodeWithText("Retriever carries on when your phone is charging.").assertExists()
        rule.onNodeWithText(Words.START_PROCESSING).assertExists()
    }

    @Test fun chargingUpLight() {
        shot("charging_up", false)
        rule.onNodeWithText("Paused · continues when the battery has a bit more charge").assertExists()
        rule.onNodeWithText("Processing starts once your battery has a bit more charge.").assertExists()
    }

    @Test fun readNowLight() {
        shot("processing", false)
        rule.onNodeWithText(Words.strings.learningYourFilesTitle).assertExists()
        rule.onNodeWithText("Pause").assertExists()
    }

    @Test fun downloadingLight() {
        shot("downloading", false)
        rule.onNodeWithText("Downloading search by description · 40%").assertExists()
        // The line is the download: no card for it as well.
        rule.onNodeWithText(Words.UPGRADE_TITLE).assertDoesNotExist()
    }

    @Test fun meaningLight() {
        shot("meaning", false)
        rule.onNodeWithText("Adding search by description").assertExists()
        rule.onNodeWithText(Words.meaningOf(2_200, 3_300)).assertExists()
    }

    @Test fun allReadLight() {
        shot("all_read", false)
        rule.onNodeWithText("All done. Retriever has processed everything it can").assertExists()
        rule.onNodeWithContentDescription("Dismiss").assertExists()
    }

    @Test fun widenLight() {
        shot("widen", false)
        rule.onNodeWithText("Retriever can see your photos and 2 folders.", substring = true).assertExists()
    }

    @Test fun widenDark() = shot("widen", true)

    /** Allow opens the all-files switch; Not now hides the row for good. */
    @Test fun widenRowRunsItsActions() {
        val seen = mutableListOf<String>()
        val actions = HomeActions(allowAccess = { seen += "allow" }, dismissWidenAccess = { seen += "not now" })
        rule.setContent { ShotTheme(false) { Screen(states.getValue("widen").first, homeActions = actions) } }
        rule.onNodeWithText(Words.strings.allowAllFiles).performClick()
        rule.onNodeWithText(Words.NOT_NOW).performClick()
        assertEquals(listOf("allow", "not now"), seen)
    }

    @Test fun noAccessLight() {
        shot("no_access", false)
        rule.onNodeWithText("Retriever can’t see your files").assertExists()
        rule.onNodeWithText("Allow access").assertExists()
        // Pictures cannot load without access: no Recently added, no On this day.
        rule.onNodeWithText(Words.RECENTLY_ADDED).assertDoesNotExist()
        rule.onNodeWithText("On this day").assertDoesNotExist()
    }

    @Test fun theOrderIsTitleStatusStarredRecentOnThisDayBrowse() {
        rule.setContent { ShotTheme(false) { Screen(base) } }
        val order =
            listOf("Retriever", Words.strings.learningYourFilesTitle, "Starred", Words.RECENTLY_ADDED, "On this day", Words.MORE_ON_PHONE)
                .map(::top)
        assertEquals(order.sorted(), order)
        rule.onNode(hasText("Retriever") and isHeading()).assertExists()
    }

    @Test fun onlyTheStateIsAnnouncedNotEachPercent() {
        rule.setContent { ShotTheme(false) { Screen(base) } }
        val title = Words.strings.learningYourFilesTitle
        rule.onNode(hasContentDescription(title) and SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertExists()
    }

    @Test fun theLineOpensReadingStatus() {
        val went = mutableListOf<Route>()
        rule.setContent { ShotTheme(false) { Screen(base, actions = SearchActions(onNavigate = { went += it })) } }
        rule.onNodeWithText(Words.strings.learningYourFilesTitle).performClick()
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
                    homeActions = HomeActions(readNow = { did += "read" }, stopReadNow = { did += "stop" }),
                    upgrade = upgrade,
                    upgradeActions = UpgradeActions(cancel = { did += "cancel" }),
                )
            }
        }
        rule.onNodeWithText(Words.START_PROCESSING).performClick()
        ui = states.getValue("processing").first
        rule.onNodeWithText("Pause").performClick()
        ui = base.copy(readingStatus = line(stop = StopReason.PAUSED))
        rule.onNodeWithText("Resume").performClick()
        ui = base
        upgrade = downloading
        rule.onNodeWithText("Stop").performClick()
        assertEquals(listOf("read", "stop", "read", "cancel"), did)
    }

    @Test fun notNowStartsProcessingFromTheExistingStatusLine() {
        var starts = 0
        rule.setContent { ShotTheme(false) { Screen(states.getValue("not_now").first, homeActions = HomeActions(readNow = { starts++ })) } }
        rule.onNodeWithText(Words.START_PROCESSING).performClick()
        assertEquals(1, starts)
    }

    // The demo flow's Home renders: the count and a bar exactly done / total on every state, none while counting.

    @Test fun demoChargingLight() {
        shot("charging", false, prefix = "demo_home")
        rule.onNodeWithText(Words.strings.learningYourFilesTitle).assertExists()
        rule.onNodeWithText(Words.readOf(30, 25_793)).assertExists()
        assertEquals(30L to 25_793L, states.getValue("charging").first.readingStatus!!.let { it.done to it.total })
    }

    @Test fun demoChargingLargestText() = shot("charging", false, fontScale = 2f, prefix = "demo_home")

    @Test fun demoUnpluggedLight() {
        shot("unplugged_demo", false, prefix = "demo_home")
        rule.onNodeWithText("Paused · continues when you plug in").assertExists()
        rule.onNodeWithText(Words.readOf(30, 25_793)).assertExists()
    }

    @Test fun demoNotNowLight() {
        shot("not_now", false, prefix = "demo_home")
        rule.onNodeWithText("Only names can be found for now").assertExists()
        rule.onNodeWithText(Words.readOf(30, 25_793)).assertExists()
        rule.onNodeWithText(Words.START_PROCESSING).assertExists()
    }

    @Test fun demoCountingLight() {
        shot("counting", false, prefix = "demo_home")
        rule.onNodeWithText("Getting ready…").assertExists()
        rule.onAllNodesWithText("files processed", substring = true).assertCountEquals(0)
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
    }

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

    @Test fun theDownloadSitsUnderTheStatusAndCanBeDismissed() {
        var dismissed = 0
        val offer = UpgradeUi(UpgradeStage.OFFER, sizeBytes = 387_710_976)
        rule.setContent { ShotTheme(false) { Screen(base, upgrade = offer, upgradeActions = UpgradeActions(notNow = { dismissed++ }), offer = true) } }
        rule.waitForIdle()
        rule.shot("clarity_home_meaning_light")
        assertTrue(top(Words.SEARCH_BY_MEANING) < top(Words.RECENTLY_ADDED))
        rule.onNodeWithText(Words.NOT_NOW).performClick()
        assertEquals(1, dismissed)
    }

    @Test fun recentlyAddedMergesTheNewestAndOpensThem() {
        val opened = mutableListOf<Pair<SourceFile, String>>()
        val went = mutableListOf<Route>()
        rule.setContent { ShotTheme(false) { Screen(base, actions = SearchActions(onOpenFile = { f, o -> opened += f to o }, onNavigate = { went += it })) } }
        rule.onNode(described("Electricity bill October.pdf")).performClick()
        rule.onNode(hasContentDescription(Words.RECENTLY_ADDED) and isHeading()).performClick()
        assertEquals(listOf(facts.newDocs[0] to "recent"), opened)
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
        val opened = mutableListOf<Pair<SourceFile, String>>()
        val went = mutableListOf<Route>()
        rule.setContent { ShotTheme(false) { Screen(base, actions = SearchActions(onOpenFile = { f, o -> opened += f to o }, onNavigate = { went += it })) } }
        rule.onNode(described("Voice note.m4a, Audio, ")).performClick()
        rule.onNode(hasContentDescription("Starred") and isHeading()).performClick()
        assertEquals(listOf(facts.starred[2] to "starred"), opened)
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

    @Test fun theSearchPillAsksTheQuestionAtTheBottomAndSettingsIsAtTheTop() {
        val went = mutableListOf<Route>()
        rule.setContent { ShotTheme(false) { Screen(base, actions = SearchActions(onNavigate = { went += it })) } }
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val pill = rule.onNodeWithText(Words.SEARCH_FIELD).fetchSemanticsNode().boundsInRoot
        assertTrue("search in the bottom half", pill.top > root.height / 2)
        rule.onNodeWithContentDescription("Settings").performClick()
        assertEquals(listOf<Route>(SettingsRoute), went)
    }

    // --- The one-time card after setup ---

    /** Setup just finished: the first search's 30 files done, the rest scheduled. */
    private val intro = base.copy(introDue = true, planned = 30, progress = afterDemo)

    private val size = 387_710_976L
    private val upgrades = mapOf(
        DescriptionState.READY to UpgradeUi(UpgradeStage.DONE, ModelState.Done, sizeBytes = size),
        DescriptionState.DOWNLOADING to UpgradeUi(UpgradeStage.DOWNLOAD, ModelState.Downloading(0.4f, false), sizeBytes = size),
        DescriptionState.PREPARING to UpgradeUi(UpgradeStage.TUNE, ModelState.Done, sizeBytes = size),
        DescriptionState.WAITING_FOR_WIFI to UpgradeUi(UpgradeStage.DOWNLOAD, ModelState.WaitingForWifi, sizeBytes = size),
        DescriptionState.FAILED to UpgradeUi(UpgradeStage.DOWNLOAD, ModelState.Failed(ModelRepository.STOPPED), sizeBytes = size),
        DescriptionState.NO_SPACE to UpgradeUi(UpgradeStage.DOWNLOAD, ModelState.Failed("Needs 0.7 GB free.", noSpace = true), sizeBytes = size),
        DescriptionState.NOT_STARTED to UpgradeUi(UpgradeStage.OFFER, ModelState.Missing, sizeBytes = size),
        DescriptionState.UNAVAILABLE to UpgradeUi(),
    )

    private val sizeText get() = Format.downloadSize(ApplicationProvider.getApplicationContext<Context>(), size)

    private fun introShot(
        name: String,
        state: DescriptionState,
        dark: Boolean = false,
        home: HomeActions = HomeActions(),
        upgrade: UpgradeActions = UpgradeActions(),
    ) {
        // The phone's own height, so the shot shows what a person sees first.
        RuntimeEnvironment.setQualifiers("+h891dp")
        rule.setContent { ShotTheme(dark) { Screen(intro, homeActions = home, upgrade = upgrades.getValue(state), upgradeActions = upgrade) } }
        rule.waitForIdle()
        rule.shot(name)
    }

    @Test fun introReady() {
        for ((state, up) in upgrades) assertEquals(state, up.description)
        introShot("home_intro_ready_light", DescriptionState.READY)
        rule.onNodeWithText(Words.HOME_INTRO_TITLE).assertIsDisplayed()
        rule.onNodeWithText(Words.recentFiles(30)).assertIsDisplayed()
        rule.onNodeWithText(Words.recentFilesBody(DescriptionState.READY)).assertIsDisplayed()
        rule.onNodeWithText(Words.otherFiles(25_763)).assertIsDisplayed()
        rule.onNodeWithText(Words.NEW_FILES).assertIsDisplayed()
        // The explanation keeps processing controls available.
        rule.onNodeWithText(Words.strings.learningYourFilesTitle).assertExists()
        rule.onAllNodesWithText(Words.TRY_AGAIN).assertCountEquals(0)
    }

    /** People off with photos read: the card's one choice, raised off it; Turn on is Settings' switch. */
    @Test fun introOffersPeople() {
        var on = 0
        RuntimeEnvironment.setQualifiers("+h891dp")
        rule.setContent { ShotTheme(false) { Screen(intro.copy(peopleOffer = true), homeActions = HomeActions(turnOnPeople = { on++ })) } }
        rule.waitForIdle()
        rule.shot("home_intro_people_light")
        rule.onNodeWithText(Words.strings.peopleRowBody(DeviceWords.ANDROID)).assertIsDisplayed()
        rule.onNodeWithText(Words.TURN_ON).performClick()
        assertEquals(1, on)
    }

    @Test fun introOffersPeopleDark() {
        rule.setContent { ShotTheme(true) { Screen(intro.copy(peopleOffer = true)) } }
        rule.waitForIdle()
        rule.shot("home_intro_people_dark")
    }

    @Test fun introOnItsWay() {
        introShot("home_intro_on_its_way_light", DescriptionState.DOWNLOADING)
        rule.onNodeWithText(Words.recentFilesBody(DescriptionState.DOWNLOADING)).assertIsDisplayed()
        // The download's own row waits for Got it.
        rule.onAllNodesWithText(Words.UPGRADE_TITLE).assertCountEquals(0)
    }

    @Test fun introWaitingForWifiOffersMobileData() {
        var mobile = false
        introShot("home_intro_wifi_light", DescriptionState.WAITING_FOR_WIFI, upgrade = UpgradeActions(useMobileData = { mobile = true }))
        rule.onNodeWithText(Words.recentFilesBody(DescriptionState.WAITING_FOR_WIFI)).assertIsDisplayed()
        rule.onNodeWithText(Words.recentFilesAction(DescriptionState.WAITING_FOR_WIFI, sizeText)!!).performClick()
        assertTrue(mobile)
    }

    @Test fun introFailedTriesAgain() {
        var downloads = 0
        introShot("home_intro_failed_light", DescriptionState.FAILED, upgrade = UpgradeActions(download = { downloads++ }))
        rule.onNodeWithText(Words.recentFilesBody(DescriptionState.FAILED)).assertIsDisplayed()
        rule.onNodeWithText(Words.TRY_AGAIN).performClick()
        assertEquals(1, downloads)
    }

    @Test fun introNotStartedDownloads() {
        var downloads = 0
        introShot("home_intro_not_started_light", DescriptionState.NOT_STARTED, upgrade = UpgradeActions(download = { downloads++ }))
        rule.onNodeWithText(Words.recentFilesBody(DescriptionState.NOT_STARTED)).assertIsDisplayed()
        rule.onNodeWithText(Words.recentFilesAction(DescriptionState.NOT_STARTED, sizeText)!!).performClick()
        assertEquals(1, downloads)
        // Not the offer's own card as well.
        rule.onAllNodesWithText(Words.UPGRADE_TITLE).assertCountEquals(0)
    }

    /** Refused for space: Android's storage screen, then Try again, as Settings' card offers. */
    @Test fun introNoSpaceFreesUpSpaceThenTriesAgain() {
        var downloads = 0
        introShot("home_intro_no_space_light", DescriptionState.NO_SPACE, upgrade = UpgradeActions(download = { downloads++ }))
        rule.onNodeWithText(Words.recentFilesBody(DescriptionState.NO_SPACE)).assertIsDisplayed()
        freeUpSpaceOpensAndroidsStorage()
        rule.onNodeWithText(Words.TRY_AGAIN).performClick()
        assertEquals(1, downloads)
    }

    /** After Got it the line names the room a refused download needs, not Wi-Fi, and offers the same two ways on. */
    @Test fun noSpaceLineNamesTheRoomAndFreesItUp() {
        var downloads = 0
        val stopped = base.copy(readingStatus = line(stop = StopReason.UNPLUGGED, pluggedIn = false))
        rule.setContent {
            ShotTheme(false) {
                Screen(stopped, upgrade = upgrades.getValue(DescriptionState.NO_SPACE), upgradeActions = UpgradeActions(download = { downloads++ }))
            }
        }
        rule.shot("clarity_home_no_space_light")
        rule.onNodeWithText(Words.NO_SPACE).assertIsDisplayed()
        rule.onNodeWithText("Needs 0.7 GB free.").assertIsDisplayed()
        rule.onAllNodesWithText("Wi-Fi", substring = true).assertCountEquals(0)
        freeUpSpaceOpensAndroidsStorage()
        rule.onNodeWithText(Words.TRY_AGAIN).performClick()
        assertEquals(1, downloads)
    }

    /** The button, not Home's own Free up space section further down. */
    private fun freeUpSpaceOpensAndroidsStorage() {
        rule.onNode(hasText(Words.FREE_UP_SPACE) and hasClickAction()).performClick()
        assertEquals(StorageManager.ACTION_MANAGE_STORAGE, shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity?.action)
    }

    @Test fun introWithNothingToDoHasNoAction() {
        var state by mutableStateOf(DescriptionState.UNAVAILABLE)
        rule.setContent { ShotTheme(false) { Screen(intro, upgrade = upgrades.getValue(state)) } }
        for (s in listOf(DescriptionState.UNAVAILABLE, DescriptionState.PREPARING)) {
            state = s
            rule.waitForIdle()
            rule.onNodeWithText(Words.recentFilesBody(s)).assertExists()
            rule.onAllNodesWithText(Words.TRY_AGAIN).assertCountEquals(0)
            rule.onAllNodesWithText(Words.USE_MOBILE_DATA, substring = true).assertCountEquals(0)
        }
    }

    @Test fun notifyAsksForTheNotice() {
        var asked = false
        introShot("home_intro_notify_light", DescriptionState.DOWNLOADING, home = HomeActions(notify = { asked = true }))
        rule.onNodeWithText(Words.NOTIFY_WHEN_SEARCHABLE).performClick()
        assertTrue(asked)
    }

    /** Once the notice was asked for, the card has nothing more to promise. */
    @Test fun notifyHiddenOnceAsked() {
        rule.setContent { ShotTheme(false) { Screen(intro, upgrade = upgrades.getValue(DescriptionState.READY)) } }
        rule.onAllNodesWithText(Words.NOTIFY_WHEN_SEARCHABLE).assertCountEquals(0)
        rule.onNodeWithText(Words.GOT_IT).assertExists()
    }

    @Test fun gotItDismissesTheCard() {
        var dismissed = false
        rule.setContent { ShotTheme(false) { Screen(intro, homeActions = HomeActions(dismissIntro = { dismissed = true })) } }
        rule.onNodeWithText(Words.GOT_IT).performClick()
        assertTrue(dismissed)
    }
}
