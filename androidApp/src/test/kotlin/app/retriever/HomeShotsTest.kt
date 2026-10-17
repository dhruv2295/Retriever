package app.retriever

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.retriever.core.HomeFacts
import app.retriever.core.IndexState
import app.retriever.core.Kind
import app.retriever.core.SourceFile
import app.retriever.core.StopReason
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

/** Renders Home's states, light and dark, to androidApp/build/shots/home_*.png. */
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
        Kind.SCREENSHOT to 158L, Kind.PDF to 1_600L, Kind.DOC to 125L, Kind.PHOTO to 22_000L,
        Kind.VIDEO to 1_700L, Kind.AUDIO to 345L, Kind.OTHER to 372L,
    )

    private val facts = HomeFacts(
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

    private val caughtUp = SearchUi(
        setUp = true,
        counts = counts,
        searchable = 26_300,
        recent = listOf("passport scan", "wifi password", "lease agreement", "older one"),
        home = facts,
        starred = 4,
    )

    private val reading = caughtUp.copy(
        searchable = 9_840,
        waiting = 16_460,
        pending = mapOf(Kind.PHOTO to 14_700L, Kind.VIDEO to 1_700L, Kind.AUDIO to 60L),
    )

    private val readingIndex = IndexState(running = true, pendingAtStart = 16_460, etaMinutes = 180)

    @Composable
    private fun State(state: String, actions: SearchActions) {
        when (state) {
            "caught_up" -> SearchContent(caughtUp, IndexState(lastStop = StopReason.DONE), actions)
            "reading" -> SearchContent(reading, readingIndex, actions)
            "paused" -> SearchContent(reading.copy(paused = true), IndexState(lastStop = StopReason.PAUSED), actions)
            // setUp needs access, so taking access away also leaves setup unfinished.
            "no_access" -> SearchContent(caughtUp.copy(hasAccess = false, setUp = false), IndexState(lastStop = StopReason.DONE), actions)
            "photos_off" -> SearchContent(reading.copy(inside = Kind.entries.toSet() - Kind.PHOTO), readingIndex, actions)
            "first_run" -> SearchContent(SearchUi(setUp = true, counts = emptyMap()), IndexState(running = true), actions)
            "empty_phone" -> SearchContent(SearchUi(setUp = true, counts = emptyMap()), IndexState(), actions)
            else -> error("no state $state")
        }
    }

    private fun shot(state: String, dark: Boolean, actions: SearchActions = SearchActions()) {
        rule.setContent { ShotTheme(dark) { State(state, actions) } }
        rule.waitForIdle()
        rule.shot("home_${state}_${if (dark) "dark" else "light"}")
    }

    @Test fun caughtUpLight() {
        shot("caught_up", false)
        rule.onNodeWithText("All caught up").assertExists()
        rule.onNodeWithText("· 26,300 files searchable").assertExists()
        rule.onNodeWithText("760 of them by name only").assertExists()
        rule.onNodeWithText("Recent searches").assertExists()
        // Three rows, not the whole history.
        rule.onNodeWithText("older one").assertDoesNotExist()
        rule.onNodeWithText("Download · Today · 2.1 MB").assertExists()
        rule.onNodeWithText("Your files").assertExists()
        rule.onNodeWithText("Tidy up").assertExists()
        rule.onNodeWithText("212 files over 100 MB · 41.0 GB").assertExists()
        // Caught up: no per-type progress, and no exact-copy total.
        rule.onAllNodesWithText("Ready").assertCountEquals(0)
        rule.onNodeWithText("Check for exact copies").assertExists()
        rule.onNodeWithContentDescription("Folders, 148").assertExists()
    }

    @Test fun aKindNotReadInsideIsFoundByName() {
        shot("photos_off", false)
        rule.onNodeWithContentDescription("Photos, 22,000 files, Found by name").assertExists()
    }

    @Test fun caughtUpDark() = shot("caught_up", true)

    @Test fun readingLight() {
        shot("reading", false)
        // Only the words are a live region; the count beside them changes without an announcement each time.
        rule.onNode(hasText("Reading your files") and SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertExists()
        rule.onNode(hasText("· 9,840 of 26,300") and SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion)).assertExists()
        rule.onNodeWithText("About 3 hours left. Faster while charging.").assertExists()
        rule.onNodeWithContentDescription("Photos, 22,000 files, 14,700 to go").assertExists()
        rule.onNodeWithContentDescription("PDFs, 1,600 files, Ready").assertExists()
        rule.onNodeWithContentDescription("Other files, 372 files, Found by name").assertExists()
    }

    @Test fun readingDark() = shot("reading", true)

    @Test fun readingOnTheChargerDoesNotAdviseCharging() {
        rule.setContent { ShotTheme(false) { SearchContent(reading.copy(charging = true), readingIndex, SearchActions()) } }
        rule.onNodeWithText("About 3 hours left.").assertExists()
        rule.onNodeWithText("Faster while charging", substring = true).assertDoesNotExist()
    }

    @Test fun pausedLight() {
        shot("paused", false)
        rule.onNodeWithText("Paused").assertExists()
        rule.onNodeWithText("· 9,840 of 26,300 searchable").assertExists()
        rule.onNodeWithText("Resume").assertExists()
    }

    @Test fun pausedDark() = shot("paused", true)

    @Test fun noAccessLight() {
        shot("no_access", false)
        rule.onNodeWithText("Retriever can't see your files").assertExists()
        rule.onNodeWithText("Allow access").assertExists()
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
        rule.setContent { ShotTheme(false) { SearchContent(caughtUp.copy(home = facts.copy(newDocs = emptyList(), newShots = emptyList(), largeCount = 0), starred = 0, recent = emptyList()), IndexState(), SearchActions()) } }
        rule.onNodeWithText("New documents").assertDoesNotExist()
        rule.onNodeWithText("New screenshots").assertDoesNotExist()
        rule.onNodeWithText("Recent searches").assertDoesNotExist()
        rule.onNodeWithText("Starred").assertDoesNotExist()
        rule.onNodeWithText("Your files").assertExists()
    }

    @Test fun triesSearchingBeforeThereIsAHistory() {
        rule.setContent { ShotTheme(false) { SearchContent(caughtUp.copy(recent = emptyList(), suggestions = listOf("wifi password")), IndexState(), SearchActions()) } }
        rule.onNodeWithText("Try searching").assertExists()
        rule.onNodeWithText("wifi password").assertExists()
    }

    @Test fun starredIsTheFirstRowAndOpensTheStarredList() {
        val went = mutableListOf<Route>()
        rule.setContent { ShotTheme(false) { SearchContent(caughtUp, IndexState(), SearchActions(onNavigate = { went += it })) } }
        rule.onNodeWithContentDescription("Starred, 4 files").performClick()
        assertEquals(listOf<Route>(BrowseRoute(title = "Starred", starredOnly = true)), went)
    }

    @Test fun gearOpensSettings() {
        val went = mutableListOf<Route>()
        rule.setContent { ShotTheme(false) { SearchContent(caughtUp, IndexState(), SearchActions(onNavigate = { went += it })) } }
        rule.onNodeWithContentDescription("Settings").performClick()
        assertEquals(listOf<Route>(SettingsRoute), went)
    }

    @Test fun resumeResumesWithoutOpeningStatus() {
        var resumed = 0
        val went = mutableListOf<Route>()
        rule.setContent { ShotTheme(false) { SearchContent(reading.copy(paused = true), IndexState(), SearchActions(onResume = { resumed++ }, onNavigate = { went += it })) } }
        rule.onNodeWithText("Resume").performClick()
        assertEquals(1, resumed)
        assertTrue(went.isEmpty())
    }

    @Test fun clearEmptiesHistoryAtOnce() {
        var cleared = 0
        rule.setContent { ShotTheme(false) { SearchContent(caughtUp, IndexState(), SearchActions(onClearRecent = { cleared++ })) } }
        rule.onNodeWithContentDescription("Clear recent searches").performClick()
        assertEquals(1, cleared)
    }

    @Test fun arrowFillsTheFieldAndRowSearches() {
        val queries = mutableListOf<String>()
        val submitted = mutableListOf<String>()
        rule.setContent { ShotTheme(false) { SearchContent(caughtUp, IndexState(), SearchActions(onQuery = { queries += it }, onSubmit = { submitted += it })) } }
        rule.onNodeWithContentDescription("Edit “passport scan”").performClick()
        rule.waitForIdle()
        assertTrue("passport scan" in queries)
        assertTrue(submitted.isEmpty())
    }

    @Test fun wideWindowHasTwoColumns() {
        RuntimeEnvironment.setQualifiers("+w900dp-h1400dp")
        rule.setContent { ShotTheme(false) { SearchContent(caughtUp, IndexState(), SearchActions()) } }
        rule.waitForIdle()
        rule.shot("home_wide_light")
    }
}
