package app.retriever

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import app.retriever.core.IndexState
import app.retriever.core.Kind
import app.retriever.core.SearchEngine
import app.retriever.core.SourceFile
import app.retriever.core.StopReason
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the search screen's states to androidApp/build/shots/search_*.png. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchShotsTest {
    @get:Rule val rule = createComposeRule()

    private fun file(name: String, kind: Kind, days: Int = 1) =
        SourceFile("demo:$name", name, "Documents", "application/octet-stream", kind, 1_200_000, 1_760_000_000_000 - days * 86_400_000L)

    private fun hit(id: Long, file: SourceFile, chunkId: Long = 0, locator: String = "", snippet: String? = null) =
        SearchEngine.Hit(file, id, chunkId, locator, snippet, 1.0 / id)

    private val counts = mapOf(Kind.PHOTO to 4_210L, Kind.SCREENSHOT to 812L, Kind.VIDEO to 96L, Kind.PDF to 143L, Kind.DOC to 57L, Kind.AUDIO to 12L)

    private val ready = SearchUi(
        setUp = true,
        counts = counts,
        searchable = 3_100,
        waiting = 2_230,
        recentFiles = listOf(
            file("IMG_2041.jpg", Kind.PHOTO),
            file("Electricity bill March.pdf", Kind.PDF),
            file("Screenshot_wifi.png", Kind.SCREENSHOT),
            file("Meeting notes.docx", Kind.DOC),
            file("Voice memo 12.m4a", Kind.AUDIO),
        ),
    )

    private val mixedHits = listOf(
        hit(1, file("Electricity bill March.pdf", Kind.PDF), chunkId = 11, locator = "p. 2", snippet = "Total \u0002bill\u0003 amount due by 15 April"),
        hit(2, file("IMG_2041.jpg", Kind.PHOTO)),
        hit(3, file("Screenshot bill payment.png", Kind.SCREENSHOT)),
        // Two name-only hits share chunk 0; keys are file ids, so they must both render.
        hit(4, file("Water bill.pdf", Kind.PDF)),
        hit(5, file("Old bill.docx", Kind.DOC)),
        hit(6, file("Birthday.mp4", Kind.VIDEO), chunkId = 60, locator = "3:41"),
    )

    @Composable
    private fun State(state: String) {
        val none = SearchActions()
        when (state) {
            "home" -> SearchContent(ready, IndexState(running = true, pendingAtStart = 2_400, etaMinutes = 95), none)
            "home_stopped" -> SearchContent(ready, IndexState(lastStop = StopReason.UNPLUGGED), none)
            "home_before_setup" -> SearchContent(SearchUi(counts = emptyMap()), IndexState(), none)
            "home_caught_up" -> SearchContent(ready.copy(waiting = 0, searchable = 5_330), IndexState(lastStop = StopReason.DONE), none)
            "recent" -> SearchPane(ready.copy(recent = listOf("wifi password", "electricity bill", "passport photo")), none)
            "examples" -> SearchPane(ready, none)
            "results" -> SearchPane(ready.copy(query = "bill", hitsFor = "bill", hits = mixedHits), none)
            "results_searching" -> SearchPane(ready.copy(query = "bill", hitsFor = "bill", hits = mixedHits, searching = true, vectorLane = false), none)
            "chip_pdf" -> SearchPane(ready.copy(query = "bill", hitsFor = "bill", chip = Kind.PDF, hits = mixedHits.filter { it.file.kind == Kind.PDF }), none)
            "similar" -> SearchPane(ready.copy(similar = Similar("IMG_2041.jpg", ByteArray(1)), hits = mixedHits.filter { it.file.kind == Kind.PHOTO }), none)
            "empty" -> SearchPane(ready.copy(query = "blue passport", hitsFor = "blue passport", hits = emptyList()), none)
            "empty_in_chip" -> SearchPane(ready.copy(query = "passport photo", hitsFor = "passport photo", chip = Kind.PDF, hits = emptyList()), none)
            "empty_stale" -> SearchPane(ready.copy(query = "invoice", hitsFor = "invoice zq", hits = emptyList()), none)
            "error" -> SearchPane(ready.copy(query = "bill", failed = true), none)
            else -> error("no state $state")
        }
    }

    private fun shot(state: String, dark: Boolean, fontScale: Float = 1f) {
        rule.setContent { ShotTheme(dark, fontScale) { State(state) } }
        rule.waitForIdle()
        rule.shot("search_${state}_${if (dark) "dark" else "light"}${if (fontScale != 1f) "_text1_5" else ""}")
    }

    @Test fun homeLight() {
        shot("home", false)
        rule.onNodeWithContentDescription("Search with a photo").assertExists()
        rule.onNodeWithText("Reading your files · 3,100 of 5,330").assertExists()
    }

    @Test fun homeDark() = shot("home", true)
    @Test fun homeBigText() = shot("home", false, 1.5f)
    @Test fun homeStoppedLight() = shot("home_stopped", false)
    @Test fun homeBeforeSetupLight() {
        shot("home_before_setup", false)
        rule.onNodeWithText("Retriever is looking for your files").assertExists()
    }

    @Test fun homeBeforeSetupDark() = shot("home_before_setup", true)
    @Test fun homeCaughtUpKeepsAWayToStatus() {
        shot("home_caught_up", false)
        rule.onNodeWithText("All caught up · 5,330 files searchable").assertExists()
    }

    @Test fun homeBeforeCountsShowsNoFirstRunMessage() {
        rule.setContent { ShotTheme(false, 1f) { SearchContent(SearchUi(setUp = true), IndexState(), SearchActions()) } }
        rule.onNodeWithText("Retriever is looking for your files").assertDoesNotExist()
    }

    @Test fun emptyInChipOffersAllFiles() {
        shot("empty_in_chip", false)
        rule.onNodeWithText("No matches in PDFs").assertExists()
        rule.onNodeWithText("Search all files").assertExists()
    }

    @Test fun staleEmptyNamesNoUnsearchedWords() {
        shot("empty_stale", false)
        rule.onNodeWithText("No matches", substring = true).assertDoesNotExist()
    }

    @Test fun screenshotWithMatchedWordsIsARow() {
        val shot = hit(7, file("Screenshot_wifi.png", Kind.SCREENSHOT), chunkId = 70, snippet = "\u0002wifi\u0003 password: hunter2")
        rule.setContent { ShotTheme(false, 1f) { SearchPane(ready.copy(query = "wifi", hitsFor = "wifi", hits = listOf(shot)), SearchActions()) } }
        rule.onNodeWithText("password: hunter2", substring = true).assertExists()
    }
    @Test fun recentLight() {
        shot("recent", false)
        rule.onNodeWithContentDescription("Remove wifi password from history").assertExists()
    }

    @Test fun recentDark() = shot("recent", true)
    @Test fun examplesLight() {
        shot("examples", false)
        rule.onNodeWithText("notes from the meeting").assertExists()
    }

    @Test fun examplesDark() = shot("examples", true)
    @Test fun resultsLight() {
        shot("results", false)
        rule.onNodeWithText("Water bill.pdf", substring = true).assertExists()
        rule.onNodeWithText("Old bill.docx", substring = true).assertExists()
        rule.onNodeWithText("6 results").assertExists()
    }

    @Test fun resultsDark() = shot("results", true)
    @Test fun resultsBigText() = shot("results", false, 1.5f)
    @Test fun resultsSearchingLight() = shot("results_searching", false)
    @Test fun chipPdfLight() = shot("chip_pdf", false)
    @Test fun chipPdfDark() = shot("chip_pdf", true)
    @Test fun similarLight() {
        shot("similar", false)
        rule.onNodeWithContentDescription("Stop searching by photo").assertExists()
    }

    @Test fun emptyLight() {
        shot("empty", false)
        rule.onNodeWithText("Try fewer words").assertExists()
    }

    @Test fun emptyDark() = shot("empty", true)
    @Test fun errorLight() = shot("error", false)
    @Test fun errorDark() = shot("error", true)
}
