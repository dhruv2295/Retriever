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
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.retriever.ShotTheme
import app.retriever.access.Photos
import app.retriever.core.HomeFacts
import app.retriever.core.IndexState
import app.retriever.core.Kind
import app.retriever.core.Prefs
import app.retriever.core.SourceFile
import app.retriever.core.StopReason
import app.retriever.core.Store
import app.retriever.core.YearMemory
import app.retriever.nav.BrowseRoute
import app.retriever.nav.Route
import app.retriever.nav.SettingsRoute
import app.retriever.search.SearchActions
import app.retriever.search.SearchContent
import app.retriever.search.SearchUi
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
import java.time.LocalDate
import java.time.ZoneId

/** Renders Home's states, light and dark, to androidApp/build/shots/home_*.png. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeShotsTest {
    @get:Rule val rule = createComposeRule()

    // Tall, so the lazy grid composes the whole page and a shot shows all of it.
    @Before fun tallWindow() = RuntimeEnvironment.setQualifiers("+h3000dp")

    private val now = System.currentTimeMillis()

    private fun file(name: String, kind: Kind, folder: String, days: Int, size: Long) =
        SourceFile("demo:$name", name, folder, "application/octet-stream", kind, size, now - days * 86_400_000L)

    private val counts = mapOf(
        Kind.SCREENSHOT to 158L,
        Kind.PDF to 1_600L,
        Kind.DOC to 125L,
        Kind.PHOTO to 22_000L,
        Kind.VIDEO to 1_700L,
        Kind.AUDIO to 345L,
        Kind.OTHER to 372L,
    )

    /** The newest [n] pictures, every fourth a video. */
    private fun media(n: Int) = (1..n).map {
        val taken = now - it * 86_400_000L
        SourceFile("demo:p$it", "p$it", "Camera", "image/jpeg", if (it % 4 == 0) Kind.VIDEO else Kind.PHOTO, 3_000_000, taken, takenAt = taken)
    }

    private val facts = HomeFacts(
        newMedia = media(15),
        starred = listOf(
            file("Passport.jpg", Kind.PHOTO, "Pictures", 3, 2_000_000),
            file("Lease_Agreement_2026.pdf", Kind.PDF, "Download", 0, 2_100_000),
            file("Trip itinerary.docx", Kind.DOC, "Documents", 14, 88_000),
            file("Voice note.m4a", Kind.AUDIO, "Recordings", 20, 300_000),
        ),
        starredCount = 4,
        newDocs = listOf(
            file("Lease_Agreement_2026.pdf", Kind.PDF, "/storage/emulated/0/Download", 0, 2_100_000),
            file("Electricity bill October.pdf", Kind.PDF, "/storage/emulated/0/Download", 1, 340_000),
            file("Trip itinerary.docx", Kind.DOC, "/storage/emulated/0/Documents", 14, 88_000),
        ),
        newShots = (1..6).map { file("Screenshot_$it.png", Kind.SCREENSHOT, "Screenshots", it, 400_000) },
        folders = 148,
        largeCount = 212,
        largeBytes = 41_000_000_000,
        nameOnly = 760,
    )

    private val recents = listOf("passport scan", "wifi password", "lease agreement", "older one")

    private val day = LocalDate.of(2026, 10, 17)

    private fun memory(year: Int, photos: Long, videos: Long) =
        YearMemory(year, photos, videos, SourceFile("demo:m$year", "m$year", "Camera", "image/jpeg", Kind.PHOTO, 1_000_000, 1L, takenAt = 1L))

    private val memories = listOf(memory(2023, 8, 0), memory(2021, 0, 1), memory(2019, 3, 2))

    private val caughtUp = HomeUi(
        setUp = true,
        counts = counts,
        readByKind = counts,
        searchable = 26_300,
        home = facts,
        starred = 4,
        memories = memories,
        memoriesDay = day,
    )

    @Composable
    private fun Screen(
        home: HomeUi,
        index: IndexState,
        actions: SearchActions = SearchActions(),
        recent: List<String> = recents,
        charging: Boolean = false,
        onResume: () -> Unit = {},
        onHideOnThisDay: () -> Unit = {},
        onCommit: (Boolean) -> Unit = {},
        celebration: Celebration = Celebration(),
    ) = SearchContent(SearchUi(recent = recent), home, actions) { padding, state, side, fill ->
        HomeBody(home, index, charging, recent, actions, onResume, {}, state, padding, side, fill, onHideOnThisDay, onCommit, celebration)
    }

    private val reading = caughtUp.copy(
        readByKind = counts.mapValues { (kind, n) ->
            (n - (mapOf(Kind.PHOTO to 14_700L, Kind.VIDEO to 1_700L, Kind.AUDIO to 60L)[kind] ?: 0L)).coerceAtLeast(0)
        },
        searchable = 9_840,
        waiting = 16_460,
        pending = mapOf(Kind.PHOTO to 14_700L, Kind.VIDEO to 1_700L, Kind.AUDIO to 60L),
    )

    private val readingIndex = IndexState(running = true, pendingAtStart = 16_460, etaMinutes = 180)

    @Composable
    private fun State(state: String, actions: SearchActions) {
        when (state) {
            "caught_up" -> Screen(caughtUp, IndexState(lastStop = StopReason.DONE), actions)

            "reading" -> Screen(reading, readingIndex, actions)

            "paused" -> Screen(reading.copy(paused = true), IndexState(lastStop = StopReason.PAUSED), actions)

            // setUp needs access, so taking access away also leaves setup unfinished.
            // The short read is done and the other 26,214 files wait for a choice.
            "commit" -> Screen(caughtUp.copy(rest = 26_214), IndexState(lastStop = StopReason.DONE), actions)

            "waiting" -> Screen(reading.copy(), IndexState(lastStop = StopReason.UNPLUGGED), actions)

            "no_access" -> Screen(caughtUp.copy(hasAccess = false, setUp = false), IndexState(lastStop = StopReason.DONE), actions)

            "videos_off" -> Screen(reading.copy(inside = Kind.entries.toSet() - Kind.VIDEO), readingIndex, actions)

            "first_run" -> Screen(HomeUi(setUp = true, counts = emptyMap()), IndexState(running = true), actions)

            "empty_phone" -> Screen(HomeUi(setUp = true, counts = emptyMap()), IndexState(), actions)

            else -> error("no state $state")
        }
    }

    private fun shot(state: String, dark: Boolean, actions: SearchActions = SearchActions()) {
        rule.setContent { ShotTheme(dark) { State(state, actions) } }
        rule.waitForIdle()
        rule.shot("home_${state}_${if (dark) "dark" else "light"}")
    }

    private fun described(prefix: String) = SemanticsMatcher("content description starts with $prefix") {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d.startsWith(prefix) } == true
    }

    // The mosaic's cells; a starred tile reads "Photo, name, starred".
    private fun cells() = rule.onAllNodes((described("Photo,") or described("Video,")) and !hasContentDescription("starred", substring = true))

    @Test fun caughtUpLight() {
        shot("caught_up", false)
        rule.onNodeWithText("All caught up").assertExists()
        rule.onNodeWithText("26,300 searchable of", substring = true).assertExists()
        rule.onNodeWithText("760 of them by name only").assertExists()
        // One chip row, no header and no Clear on Home.
        rule.onNodeWithText("Recent searches").assertDoesNotExist()
        rule.onNodeWithText("passport scan").assertExists()
        rule.onNodeWithContentDescription("Photos and videos, 22,000 photos, 1,700 videos").assertExists()
        rule.onNodeWithContentDescription("Screenshots, 158").assertExists()
        rule.onNodeWithContentDescription("Documents, 1,600 PDFs, 125 documents").assertExists()
        rule.onNodeWithText("Today · 2.1 MB").assertExists()
        rule.onNodeWithContentDescription("PDF, Lease_Agreement_2026.pdf, Today, 2.1 MB").assertExists()
        // Caught up: no pill.
        rule.onNodeWithText("to go", substring = true).assertDoesNotExist()
        rule.onNodeWithText("More on your phone").assertExists()
        rule.onNodeWithText("Tidy up").assertExists()
        rule.onNodeWithText("212 files over 100 MB · 41.0 GB").assertExists()
        // Caught up: no per-type progress, and no exact-copy total.
        rule.onAllNodesWithText("Ready").assertCountEquals(0)
        rule.onNodeWithText("Check for exact copies").assertExists()
        rule.onNodeWithContentDescription("Folders, 148").assertExists()
        rule.onNodeWithContentDescription("Videos, 1,700 files").assertExists()
        rule.onNodeWithContentDescription("Documents, 125 files").assertExists()
        rule.onNodeWithContentDescription("Audio, 345 files").assertExists()
        rule.onNodeWithText("372 · Found by name").assertExists()
        rule.onNodeWithContentDescription("Starred, 4").assertExists()
        rule.onNodeWithText("On this day").assertExists()
    }

    @Test fun aKindNotReadInsideIsFoundByName() {
        shot("videos_off", false)
        rule.onNodeWithContentDescription("Videos, 1,700 files, Found by name").assertExists()
    }

    @Test fun caughtUpDark() = shot("caught_up", true)

    @Test fun readingLight() {
        shot("reading", false)
        // Only the words are a live region; the count beside them changes without an announcement each time.
        rule.onNode(hasText("Reading your files") and SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertExists()
        rule.onNode(hasText("· About 3 hours left. Faster while charging.") and SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion)).assertExists()
        rule.onNodeWithText("About 3 hours left. Faster while charging.", substring = true).assertExists()
        rule.onNodeWithContentDescription("Videos, 1,700 files, Waiting").assertExists()
        rule.onNodeWithContentDescription("Audio, 345 files, 60 to go").assertExists()
        // Pictures are never greyed out; the big cell says how many photos are left, and headers say where each stands.
        rule.onNode(described("Photo,") and hasText("14,700 to go")).assertExists()
        rule.onNodeWithContentDescription("Screenshots, 158, Ready").assertExists()
        rule.onNodeWithContentDescription("Documents, 1,600 PDFs, 125 documents, Ready").assertExists()
        rule.onNodeWithContentDescription("Photos and videos, 22,000 photos, 1,700 videos").assertExists()
        cells().assertCountEquals(6)
        rule.onNodeWithContentDescription("Other files, 372 files, Found by name").assertExists()
    }

    @Test fun readingDark() = shot("reading", true)

    @Test fun readingOnTheChargerDoesNotAdviseCharging() {
        rule.setContent { ShotTheme(false) { Screen(reading, readingIndex, charging = true) } }
        rule.onNodeWithText("About 3 hours left.", substring = true).assertExists()
        rule.onNodeWithText("Faster while charging", substring = true).assertDoesNotExist()
    }

    @Test fun pausedLight() {
        shot("paused", false)
        rule.onNodeWithText("Paused").assertExists()
        rule.onNodeWithText("searchable of 26,300", substring = true).assertExists()
        rule.onNodeWithText("Resume").assertExists()
    }

    @Test fun pausedDark() = shot("paused", true)

    @Test fun commitCardLight() {
        shot("commit", false)
        rule.onNodeWithText("Read the other 26,214?").assertExists()
        rule.onNodeWithText("Read everything").assertExists()
        // No hours until the estimate is calibrated on a device.
        rule.onNodeWithText("about", substring = true).assertDoesNotExist()
        // The short read still running is the status card's, not a choice yet.
        rule.onNodeWithText("All caught up").assertExists()
    }

    @Test fun commitCardDark() = shot("commit", true)

    @Test fun readEverythingOpensTheThreeAnswersAsASheet() {
        val chosen = mutableListOf<Boolean>()
        rule.setContent { ShotTheme(false) { Screen(caughtUp.copy(rest = 26_214), IndexState(lastStop = StopReason.DONE), onCommit = { chosen += it }) } }
        rule.onNodeWithText("Read while charging").assertDoesNotExist()
        rule.onNodeWithText("Read everything").performClick()
        rule.onNodeWithText("Read now").assertExists()
        rule.onNodeWithText("Not yet").assertExists()
        rule.onNodeWithText("Read while charging").performClick()
        rule.waitForIdle()
        assertEquals(listOf(true), chosen)
        // The sheet closes on a choice.
        rule.onNodeWithText("Read while charging").assertDoesNotExist()
    }

    @Test fun notYetClosesTheSheetAndChoosesNothing() {
        val chosen = mutableListOf<Boolean>()
        rule.setContent { ShotTheme(false) { Screen(caughtUp.copy(rest = 26_214), IndexState(lastStop = StopReason.DONE), onCommit = { chosen += it }) } }
        rule.onNodeWithText("Read everything").performClick()
        rule.onNodeWithText("Not yet").performClick()
        rule.waitForIdle()
        assertTrue(chosen.isEmpty())
        rule.onNodeWithText("Not yet").assertDoesNotExist()
        rule.onNodeWithText("Read the other 26,214?").assertExists()
    }

    @Test fun noCommitCardWhileTheShortReadRunsOrOnceEverythingIsChosen() {
        rule.setContent { ShotTheme(false) { Screen(reading.copy(rest = 26_214), readingIndex) } }
        rule.onNodeWithText("Read the other", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Reading your files").assertExists()
    }

    @Test fun commitCardIsGoneOnceEverythingIsChosen() {
        rule.setContent { ShotTheme(false) { Screen(caughtUp, IndexState(lastStop = StopReason.DONE)) } }
        rule.onNodeWithText("Read the other", substring = true).assertDoesNotExist()
    }

    @Test fun statusCardWalksWhileReading() {
        shot("reading", false)
        rule.onNodeWithText("Reading your files").assertExists()
    }

    @Test fun statusCardRestsWhenPaused() {
        shot("paused", false)
        rule.onNodeWithText("Paused").assertExists()
    }

    @Test fun statusCardRestsWhileWaiting() {
        shot("waiting", false)
        rule.onNodeWithText("Waiting for the charger").assertExists()
    }

    @Test fun theDogPopsOnceWhenFirstFullyCaughtUp() {
        var done = 0
        rule.setContent {
            ShotTheme(false) { Screen(caughtUp, IndexState(lastStop = StopReason.DONE), celebration = Celebration(due = { true }, done = { done++ })) }
        }
        // The haptic and the mark come 0.2 s after the dog appears, not before.
        rule.mainClock.advanceTimeBy(100)
        assertEquals(0, done)
        rule.mainClock.advanceTimeBy(300)
        rule.waitForIdle()
        assertEquals(1, done)
        rule.onNodeWithText("All caught up").assertExists()
    }

    @Test fun nothingPopsWhileTheRestWaitsOrWhenSpent() {
        var done = 0
        rule.setContent {
            ShotTheme(false) {
                Screen(caughtUp.copy(rest = 5), IndexState(lastStop = StopReason.DONE), celebration = Celebration(due = { true }, done = { done++ }))
            }
        }
        rule.waitForIdle()
        assertEquals(0, done)
    }

    @Test fun noAccessLight() {
        shot("no_access", false)
        rule.onNodeWithText("Retriever can't see your files").assertExists()
        rule.onNodeWithText("Allow access").assertExists()
        // Pictures cannot load without access: no mosaic or screenshots, documents stay as icon cards.
        cells().assertCountEquals(0)
        rule.onNodeWithContentDescription("Screenshots, 158").assertDoesNotExist()
        rule.onNodeWithContentDescription("Documents, 1,600 PDFs, 125 documents").assertExists()
        rule.onNodeWithText("All caught up", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Finishing setup", substring = true).assertDoesNotExist()
    }

    @Test fun noAccessDark() = shot("no_access", true)

    @Test fun firstRunLight() {
        shot("first_run", false)
        rule.onNodeWithText("Looking for your files").assertExists()
        // One message, not a status line over the illustration.
        rule.onAllNodesWithText("Looking for", substring = true).assertCountEquals(1)
    }

    @Test fun firstRunDark() = shot("first_run", true)

    @Test fun emptyPhoneLight() {
        shot("empty_phone", false)
        rule.onNodeWithText("Nothing to search yet").assertExists()
        rule.onNodeWithText("Looking for your files").assertDoesNotExist()
    }

    @Test fun emptyPhoneDark() = shot("empty_phone", true)

    @Test fun noSectionIsDrawnEmpty() {
        rule.setContent {
            ShotTheme(false) {
                Screen(
                    caughtUp.copy(
                        home = facts.copy(
                            newMedia = emptyList(),
                            newDocs = emptyList(),
                            newShots = emptyList(),
                            starred = emptyList(),
                            starredCount = 0,
                            largeCount = 0,
                        ),
                        starred = 0,
                        memories = emptyList(),
                    ),
                    IndexState(),
                    recent = emptyList(),
                )
            }
        }
        rule.onNodeWithContentDescription("Photos and videos, 22,000 photos, 1,700 videos").assertDoesNotExist()
        rule.onNodeWithContentDescription("Documents, 1,600 PDFs, 125 documents").assertDoesNotExist()
        rule.onNodeWithContentDescription("Screenshots, 158").assertDoesNotExist()
        rule.onNodeWithText("Starred").assertDoesNotExist()
        rule.onNodeWithText("More on your phone").assertExists()
    }

    @Test fun triesSearchingBeforeThereIsAHistory() {
        rule.setContent { ShotTheme(false) { Screen(caughtUp.copy(suggestions = listOf("wifi password", "a", "b", "c")), IndexState(), recent = emptyList()) } }
        rule.onNodeWithText("Try searching").assertExists()
        rule.onNodeWithContentDescription("Try searching wifi password").assertExists()
        // Core supplies four to six content terms.
        rule.onNodeWithText("c").assertExists()
    }

    @Test fun suggestionsAreNotDrawnOverAHistory() {
        rule.setContent { ShotTheme(false) { Screen(caughtUp.copy(suggestions = listOf("wifi password")), IndexState()) } }
        rule.onNodeWithText("Try searching").assertDoesNotExist()
    }

    @Test fun clearingTheHistoryBringsTheSuggestionsBack() {
        var recent by mutableStateOf(recents)
        rule.setContent { ShotTheme(false) { Screen(caughtUp.copy(suggestions = listOf("wifi password")), IndexState(), recent = recent) } }
        rule.onNodeWithText("Try searching").assertDoesNotExist()
        recent = emptyList()
        rule.onNodeWithText("Try searching").assertExists()
    }

    @Test fun aChipFillsTheFieldAndSearches() {
        val submitted = mutableListOf<String>()
        rule.setContent { ShotTheme(false) { Screen(caughtUp, IndexState(), SearchActions(onSubmit = { submitted += it })) } }
        rule.onNodeWithText("passport scan").performClick()
        rule.waitForIdle()
        assertEquals(listOf("passport scan"), submitted)
    }

    @Test fun headersAreDoorwaysToTheirLists() {
        val went = mutableListOf<Route>()
        rule.setContent { ShotTheme(false) { Screen(caughtUp, IndexState(), SearchActions(onNavigate = { went += it })) } }
        rule.onNodeWithContentDescription("Photos and videos, 22,000 photos, 1,700 videos").performClick()
        rule.onNodeWithContentDescription("Screenshots, 158").performClick()
        rule.onNodeWithContentDescription("Documents, 1,600 PDFs, 125 documents").performClick()
        assertEquals(
            listOf<Route>(
                BrowseRoute(kind = "PHOTO", title = "Photos"),
                BrowseRoute(kind = "SCREENSHOT", title = "Screenshots"),
                BrowseRoute(kind = "PDF", title = "PDFs"),
            ),
            went,
        )
    }

    @Test fun aCellOpensItsFile() {
        val opened = mutableListOf<SourceFile>()
        rule.setContent { ShotTheme(false) { Screen(caughtUp, IndexState(), SearchActions(onOpenFile = { opened += it })) } }
        cells().onFirst().performClick()
        rule.onAllNodes(described("Screenshot,")).onFirst().performClick()
        assertEquals(listOf(facts.newMedia[0], facts.newShots[0]), opened)
    }

    private fun mosaic(n: Int, expected: Int, dark: Boolean) {
        rule.setContent { ShotTheme(dark) { Screen(caughtUp.copy(home = facts.copy(newMedia = media(n))), IndexState(lastStop = StopReason.DONE)) } }
        rule.waitForIdle()
        cells().assertCountEquals(expected)
        rule.shot("home_mosaic_${n}_${if (dark) "dark" else "light"}")
    }

    // None, one wide cell, two squares, a big cell with a stack (also for four and five), the full block.
    @Test fun mosaic0() = mosaic(0, 0, false)

    @Test fun mosaic1() = mosaic(1, 1, false)

    @Test fun mosaic2() = mosaic(2, 2, false)

    @Test fun mosaic3() = mosaic(3, 3, false)

    @Test fun mosaic4() = mosaic(4, 3, false)

    @Test fun mosaic5() = mosaic(5, 3, false)

    @Test fun mosaic6() = mosaic(6, 6, false)

    @Test fun mosaic15OnAPhone() = mosaic(15, 6, false)

    @Test fun mosaic0Dark() = mosaic(0, 0, true)

    @Test fun mosaic1Dark() = mosaic(1, 1, true)

    @Test fun mosaic2Dark() = mosaic(2, 2, true)

    @Test fun mosaic3Dark() = mosaic(3, 3, true)

    @Test fun mosaic5Dark() = mosaic(5, 3, true)

    @Test fun mosaic6Dark() = mosaic(6, 6, true)

    @Test fun theMosaicHasNoPillWhenPhotosAreRead() {
        rule.setContent { ShotTheme(false) { Screen(reading.copy(pending = mapOf(Kind.VIDEO to 100L)), readingIndex) } }
        rule.onNode(described("Photo,") and hasText("to go", substring = true)).assertDoesNotExist()
    }

    @Test fun thePillSaysPausedAndIsPartOfTheBigCell() {
        rule.setContent { ShotTheme(false) { Screen(reading.copy(paused = true), IndexState(lastStop = StopReason.PAUSED)) } }
        rule.onNode(described("Photo,") and hasText("14,700 to go · Paused")).assertExists()
        rule.onNode(hasContentDescription("14,700 photos still to read", substring = true)).assertExists()
    }

    @Test fun aVideoHasABadge() {
        rule.setContent { ShotTheme(false) { Screen(caughtUp, IndexState()) } }
        // Every fourth fixture is a video, so the first six hold one.
        rule.onAllNodes(described("Video,")).assertCountEquals(1)
    }

    @Test fun largeTextWrapsHeadersAndClipsNothing() {
        tallWindowForBigText(2f)
        rule.setContent { ShotTheme(false, fontScale = 2f) { Screen(reading, readingIndex) } }
        rule.waitForIdle()
        rule.shot("home_reading_2x")
        rule.onNodeWithContentDescription("Documents, 1,600 PDFs, 125 documents, Ready").assertExists()
        cells().assertCountEquals(6)
    }

    @Test fun theStarredHeaderOpensTheStarredList() {
        val went = mutableListOf<Route>()
        rule.setContent { ShotTheme(false) { Screen(caughtUp, IndexState(), SearchActions(onNavigate = { went += it })) } }
        rule.onNodeWithContentDescription("Starred, 4").performClick()
        assertEquals(listOf<Route>(BrowseRoute(title = "Starred", starredOnly = true)), went)
    }

    @Test fun starredShowsPicturesAndKindTilesAndOpensAFile() {
        val opened = mutableListOf<SourceFile>()
        rule.setContent { ShotTheme(false) { Screen(caughtUp, IndexState(), SearchActions(onOpenFile = { opened += it })) } }
        rule.onNodeWithContentDescription("Photo, Passport.jpg, starred").assertExists()
        rule.onNodeWithContentDescription("PDF, Lease_Agreement_2026.pdf, starred").assertExists()
        // A document and a recording have no picture: a tile with the kind's icon and the name.
        rule.onNodeWithContentDescription("Document, Trip itinerary.docx, starred").assertExists()
        rule.onNodeWithContentDescription("Audio, Voice note.m4a, starred").performClick()
        assertEquals(listOf(facts.starred[3]), opened)
    }

    @Test fun starredIsNotDrawnAtZero() {
        rule.setContent { ShotTheme(false) { Screen(caughtUp.copy(home = facts.copy(starred = emptyList(), starredCount = 0)), IndexState()) } }
        rule.onNodeWithContentDescription("Starred, 0").assertDoesNotExist()
        rule.onNodeWithText("Starred").assertDoesNotExist()
    }

    @Test fun onThisDayShowsAYearCardEachAndOpensItsDay() {
        val went = mutableListOf<Route>()
        rule.setContent { ShotTheme(false) { Screen(caughtUp, IndexState(), SearchActions(onNavigate = { went += it })) } }
        rule.onNodeWithText("On this day").assertExists()
        rule.onNodeWithText("5 photos and videos").assertExists()
        rule.onNodeWithText("1 video").assertExists()
        rule.onNodeWithText("8 photos").assertExists()
        rule.onNode(described("2021, 1 video from")).assertExists()
        rule.onNode(described("2023, 8 photos from")).performClick()
        val (from, to) = dayRange(LocalDate.of(2023, 10, 17), ZoneId.systemDefault())
        val route = went.single() as BrowseRoute
        assertEquals(from, route.takenFrom)
        assertEquals(to, route.takenTo)
        assertTrue(route.title.contains("2023"))
    }

    @Test fun aLongPressOnAYearCardHidesOnThisDay() {
        var hidden = 0
        rule.setContent { ShotTheme(false) { Screen(caughtUp, IndexState(), onHideOnThisDay = { hidden++ }) } }
        rule.onNode(described("2023, 8 photos from")).performTouchInput { longClick() }
        assertEquals(1, hidden)
    }

    private fun noOnThisDay(ui: HomeUi) {
        rule.setContent { ShotTheme(false) { Screen(ui, IndexState()) } }
        rule.onNodeWithText("On this day").assertDoesNotExist()
    }

    @Test fun onThisDayIsGoneWhenHidden() = noOnThisDay(caughtUp.copy(showOnThisDay = false))

    @Test fun onThisDayIsGoneWithoutAccess() = noOnThisDay(caughtUp.copy(hasAccess = false))

    @Test fun onThisDayIsGoneWithoutMemories() = noOnThisDay(caughtUp.copy(memories = emptyList()))

    @Test fun theStatusSwitchBringsOnThisDayBack() {
        val set = mutableListOf<Boolean>()
        rule.setContent {
            ShotTheme(false) {
                StatusContent(
                    Store.Counts(emptyMap(), emptyMap(), 0),
                    Prefs(showOnThisDay = false),
                    IndexState(),
                    emptyList(),
                    skippedOpen = false,
                    batteryHint = false,
                    actions = StatusActions(setOnThisDay = {
                        set +=
                            it
                    }),
                )
            }
        }
        rule.onNodeWithText("Show On this day on Home").performClick()
        assertEquals(listOf(true), set)
    }

    @Test fun moreOnYourPhoneListsOnlyKindsWithFilesInAFixedOrder() {
        val order = { ui: HomeUi -> moreTiles(ui).map { it.label } }
        assertEquals(listOf("Videos", "Documents", "Audio", "Other files", "Folders"), order(caughtUp))
        // Documents only beside PDFs; the others drop out at zero.
        assertEquals(listOf("Videos", "Audio", "Other files", "Folders"), order(caughtUp.copy(counts = counts + (Kind.PDF to 0L))))
        assertEquals(
            listOf("Audio", "Other files"),
            order(caughtUp.copy(counts = counts + (Kind.VIDEO to 0L) + (Kind.DOC to 0L), home = facts.copy(folders = 0))),
        )
        assertEquals(emptyList<String>(), order(caughtUp.copy(counts = mapOf(Kind.PHOTO to 5L), home = facts.copy(folders = 0))))
    }

    @Test fun gearOpensSettings() {
        val went = mutableListOf<Route>()
        rule.setContent { ShotTheme(false) { Screen(caughtUp, IndexState(), SearchActions(onNavigate = { went += it })) } }
        rule.onNodeWithContentDescription("Settings").performClick()
        assertEquals(listOf<Route>(SettingsRoute), went)
    }

    @Test fun resumeResumesWithoutOpeningStatus() {
        var resumed = 0
        val went = mutableListOf<Route>()
        rule.setContent {
            ShotTheme(false) { Screen(reading.copy(paused = true), IndexState(), SearchActions(onNavigate = { went += it }), onResume = { resumed++ }) }
        }
        rule.onNodeWithText("Resume").performClick()
        assertEquals(1, resumed)
        assertTrue(went.isEmpty())
    }

    @Test fun wideWindowHasTwoColumns() {
        RuntimeEnvironment.setQualifiers("+w900dp-h1400dp")
        rule.setContent { ShotTheme(false) { Screen(caughtUp, IndexState(), SearchActions()) } }
        rule.waitForIdle()
        // 900 dp holds more columns of the mosaic: 3 x cols - 3 cells, 6 on a phone.
        assertTrue(cells().fetchSemanticsNodes().size > 6)
        rule.shot("home_wide_light")
    }
}
