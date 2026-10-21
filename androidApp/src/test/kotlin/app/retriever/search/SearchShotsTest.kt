package app.retriever.search

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.retriever.ShotTheme
import app.retriever.core.DeviceWords
import app.retriever.core.IndexState
import app.retriever.core.Kind
import app.retriever.core.ParsedQuery
import app.retriever.core.ReadExtent
import app.retriever.core.ReadingPolicy
import app.retriever.core.SearchEngine
import app.retriever.core.SearchPhase
import app.retriever.core.SourceFile
import app.retriever.core.StopReason
import app.retriever.core.UpgradeStage
import app.retriever.core.Words
import app.retriever.home.HomeBody
import app.retriever.home.HomeUi
import app.retriever.nav.Route
import app.retriever.nav.SettingsRoute
import app.retriever.shot
import app.retriever.tallWindowForBigText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** [this] showing a finished answer of [hits] to [query]. Set chip, starred and photo first: the question includes them. */
internal fun SearchUi.answered(
    query: String,
    hits: List<SearchEngine.Hit>,
    strongCount: Int = hits.size,
    parsed: ParsedQuery? = null,
    relaxed: Boolean = false,
    complete: Boolean = true,
): SearchUi = copy(query = query).let {
    it.copy(phase = SearchPhase.Results(it.question, SearchEngine.SearchResult(hits, it.vectorLane, strongCount, parsed, relaxed), complete))
}

/** Renders the search screen's states to androidApp/build/shots/search_*.png. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchShotsTest {
    @get:Rule val rule = createComposeRule()

    private fun file(name: String, kind: Kind, days: Int = 1) =
        SourceFile("demo:$name", name, "Documents", "application/octet-stream", kind, 1_200_000, 1_760_000_000_000 - days * 86_400_000L)

    private fun hit(id: Long, file: SourceFile, chunkId: Long = 0, locator: String = "", snippet: String? = null) =
        SearchEngine.Hit(file, id, chunkId, locator, snippet, 1.0 / id)

    private val counts = mapOf(Kind.PHOTO to 4_210L, Kind.SCREENSHOT to 812L, Kind.VIDEO to 96L, Kind.PDF to 143L, Kind.DOC to 57L, Kind.AUDIO to 12L)

    private val ready = SearchUi(setUp = true)

    private val readyHome = HomeUi(
        libraryChosen = true,
        counts = counts,
        contentRead = 3_100,
        waiting = 2_230,
        suggestions = listOf("wifi password", "electricity bill", "notes from the meeting"),
    )

    /** Core's live status for [index], as Home gets it; Home shows no reading line before it arrives. */
    private fun HomeUi.live(index: IndexState) =
        copy(readingStatus = ReadingPolicy.decide(progress, index.lastStop, index.running, index.currentFile != null, DeviceWords.ANDROID))

    @Composable
    @NonRestartableComposable
    private fun Screen(ui: SearchUi, home: HomeUi, index: IndexState, actions: SearchActions) =
        SearchContent(ui, home, actions) { padding, state, side, _ -> HomeBody(home, index, actions, state, padding, side) }

    @Composable
    @NonRestartableComposable
    private fun Pane(ui: SearchUi, actions: SearchActions, home: HomeUi = readyHome) = SearchPane(ui, home, actions)

    private val mixedHits = listOf(
        hit(1, file("Electricity bill March.pdf", Kind.PDF), chunkId = 11, locator = "p. 2", snippet = "Total \u0002bill\u0003 amount due by 15 April"),
        hit(2, file("IMG_2041.jpg", Kind.PHOTO)),
        hit(3, file("Screenshot bill payment.png", Kind.SCREENSHOT)),
        // Two name-only hits share chunk 0; keys are file ids, so they must both render.
        hit(4, file("Water bill.pdf", Kind.PDF)),
        hit(5, file("Old bill.docx", Kind.DOC)),
        hit(6, file("Birthday.mp4", Kind.VIDEO), chunkId = 60, locator = "3:41"),
    )

    private val understood = ParsedQuery("bill", setOf(Kind.SCREENSHOT), 1, 2, listOf("Screenshots", "last week"), dateLabel = "last week")

    @Composable
    private fun State(state: String) {
        val none = SearchActions()
        when (state) {
            "home" -> IndexState(
                running = true,
                pendingAtStart = 2_400,
                etaMinutes = 95,
                currentFile = SourceFile("demo:active", "active", "", "text/plain", Kind.DOC, 1, 1),
            ).let { Screen(ready, readyHome.live(it), it, none) }

            "home_stopped" -> IndexState(lastStop = StopReason.UNPLUGGED).let { Screen(ready, readyHome.live(it), it, none) }

            "home_before_setup" -> Screen(SearchUi(), HomeUi(counts = emptyMap()), IndexState(), none)

            "home_caught_up" -> IndexState(lastStop = StopReason.DONE).let {
                Screen(ready, readyHome.copy(waiting = 0, contentRead = 5_330).live(it), it, none)
            }

            "recent" -> Pane(ready.copy(recent = listOf("wifi password", "electricity bill", "passport photo")), none)

            "examples" -> Pane(ready, none)

            "results" -> Pane(ready.answered("bill", mixedHits), none)

            "results_less_likely" -> Pane(ready.answered("bill", mixedHits, strongCount = 2), none)

            "results_nothing_well" -> Pane(ready.answered("bill", mixedHits, strongCount = 0), none)

            "results_first_part" -> Pane(
                ready.copy(extents = mapOf(1L to ReadExtent.FIRST_PART, 4L to ReadExtent.DEEPENING, 6L to ReadExtent.FIRST_PART)).answered("bill", mixedHits),
                none,
            )

            "results_searching" -> Pane(ready.copy(searching = true, vectorLane = false).answered("bill", mixedHits, complete = false), none)

            "results_understood" -> Pane(ready.answered("bill screenshots last week", mixedHits, parsed = understood), none, readyHome.copy(starred = 2))

            "results_relaxed" -> Pane(
                ready.copy(onlyStarred = true).answered("bill screenshots last week", mixedHits, parsed = understood, relaxed = true),
                none,
                readyHome.copy(starred = 2),
            )

            "chip_pdf" -> Pane(ready.copy(chip = Kind.PDF).answered("bill", mixedHits.filter { it.file.kind == Kind.PDF }), none)

            "similar" -> Pane(
                ready.copy(similar = Similar("demo:IMG_2041.jpg", "IMG_2041.jpg")).answered(
                    "",
                    mixedHits.filter {
                        it.file.kind ==
                            Kind.PHOTO
                    },
                ),
                none,
            )

            "empty" -> Pane(ready.answered("blue passport", emptyList()), none)

            "empty_in_chip" -> Pane(ready.copy(chip = Kind.PDF).answered("passport photo", emptyList()), none)

            "empty_stale" -> Pane(ready.answered("invoice zq", emptyList()).copy(query = "invoice"), none)

            "error" -> Pane(ready.copy(query = "bill").let { it.copy(phase = SearchPhase.Failed(it.question)) }, none)

            else -> error("no state $state")
        }
    }

    private fun shot(state: String, dark: Boolean, fontScale: Float = 1f) {
        tallWindowForBigText(fontScale)
        rule.setContent { ShotTheme(dark, fontScale) { State(state) } }
        rule.waitForIdle()
        rule.shot("search_${state}_${if (dark) "dark" else "light"}${if (fontScale != 1f) "_text1_5" else ""}")
    }

    @Test fun homeLight() {
        shot("home", false)
        rule.onNodeWithContentDescription("Search with a photo").assertExists()
        rule.onNodeWithText("Reading your files", substring = true).assertExists()
        rule.onNodeWithText(Words.SEARCH_FIELD).assertExists()
    }

    @Test fun homeDark() = shot("home", true)

    @Test fun homeBigText() = shot("home", false, 1.5f)

    @Test fun homeStoppedLight() = shot("home_stopped", false)

    @Test fun homeBeforeSetupLight() {
        shot("home_before_setup", false)
        rule.onNodeWithText("Looking for your files").assertExists()
    }

    @Test fun homeBeforeSetupDark() = shot("home_before_setup", true)

    @Test fun homeCaughtUpKeepsAWayToStatus() {
        shot("home_caught_up", false)
        rule.onNodeWithText("All done", substring = true).assertExists()
    }

    @Test fun homeBeforeCountsShowsNoFirstRunMessage() {
        rule.setContent { ShotTheme(false, 1f) { Screen(SearchUi(setUp = true), HomeUi(libraryChosen = true), IndexState(), SearchActions()) } }
        rule.onNodeWithText("Looking for your files").assertDoesNotExist()
        rule.onNodeWithText("Nothing to search yet").assertDoesNotExist()
    }

    /**
     * The expanded bar is a dialog window Robolectric can't capture, so its body is drawn on the bar's own container
     * colour: a recent row with an opaque container shows as a band there.
     */
    @Test fun expandedBarDarkWithRecents() {
        rule.setContent {
            ShotTheme(true) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Pane(ready.copy(recent = listOf("wifi password", "electricity bill")), SearchActions())
                }
            }
        }
        rule.onNodeWithContentDescription("Remove electricity bill from history").assertExists()
        rule.shot("search_expanded_recent_dark")
    }

    @Test fun emptyInChipOffersAllFiles() {
        shot("empty_in_chip", false)
        rule.onNodeWithText("No matches in your PDFs").assertExists()
        rule.onNodeWithText("Search all files").assertExists()
    }

    @Test fun emptyInStarredSaysSoAndSearchAllClearsBoth() {
        var chip: Kind? = Kind.PDF
        var starredOnly: Boolean? = null
        rule.setContent {
            ShotTheme(false, 1f) {
                Pane(
                    ready.copy(onlyStarred = true).answered("beach", emptyList()),
                    SearchActions(onChip = { chip = it }, onStarredOnly = { starredOnly = it }),
                    readyHome.copy(starred = 2),
                )
            }
        }
        rule.onNodeWithText("No matches in your starred files").assertExists()
        rule.onNodeWithText("Search all files").performClick()
        assertEquals(null, chip)
        assertEquals(false, starredOnly)
    }

    @Test fun staleEmptyNamesNoUnsearchedWords() {
        shot("empty_stale", false)
        rule.onNodeWithText("No matches", substring = true).assertDoesNotExist()
    }

    @Test fun screenshotWithMatchedWordsIsARow() {
        val shot = hit(7, file("Screenshot_wifi.png", Kind.SCREENSHOT), chunkId = 70, snippet = "\u0002wifi\u0003 password: hunter2")
        rule.setContent { ShotTheme(false, 1f) { Pane(ready.answered("wifi", listOf(shot)), SearchActions()) } }
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
        // Words from the person's own files, labelled, so they never read as random.
        rule.onNodeWithText(Words.TRY_LABEL).assertExists()
    }

    @Test fun examplesDark() = shot("examples", true)

    @Test fun resultsLight() {
        shot("results", false)
        rule.onNodeWithText("Water bill.pdf", substring = true).assertExists()
        rule.onNodeWithText("Old bill.docx", substring = true).assertExists()
    }

    @Test fun resultsDark() = shot("results", true)

    @Test fun understoodFiltersCanBeDroppedAndStarredIsAChip() {
        var asTyped = 0
        var starredOnly: Boolean? = null
        rule.setContent {
            ShotTheme(false, 1f) {
                Pane(
                    ready.answered("bill screenshots last week", mixedHits, parsed = understood),
                    SearchActions(onWordsAsTyped = { asTyped++ }, onStarredOnly = { starredOnly = it }),
                    readyHome.copy(starred = 2),
                )
            }
        }
        rule.onNodeWithText("Screenshots · last week").assertExists()
        // The kind the words asked for shows as the picked chip, not "All".
        rule.onNode(hasText("Screenshots") and isSelected()).assertExists()
        rule.onNode(hasText("All") and isSelected()).assertDoesNotExist()
        rule.onNodeWithContentDescription("Search the words as typed instead").performClick()
        assertEquals(1, asTyped)
        rule.onNodeWithText("Starred").performClick()
        assertEquals(true, starredOnly)
        rule.onNodeWithText("so here is everything", substring = true).assertDoesNotExist()
    }

    @Test fun understoodLight() = shot("results_understood", false)

    @Test fun relaxedSaysWhatWasDropped() {
        shot("results_relaxed", false)
        // The starred chip stayed on, so the note says the search kept to starred files.
        rule.onNodeWithText("No screenshots from last week, so here are all your starred files.").assertExists()
    }

    @Test fun longPressOffersAStarAndUndoTakesItBack() {
        val stars = mutableListOf<Boolean>()
        rule.setContent {
            ShotTheme(false, 1f) {
                Pane(ready.copy(starredUris = setOf("demo:Old bill.docx")).answered("bill", mixedHits), SearchActions(onStar = { _, on -> stars += on }))
            }
        }
        rule.onNodeWithText("Water bill.pdf", substring = true).performTouchInput { longClick() }
        rule.onNodeWithText("Remove from Starred").assertDoesNotExist()
        rule.onNodeWithText("Add to Starred").performClick()
        assertEquals(listOf(true), stars)
        rule.onNodeWithText("Starred").assertExists()
        rule.onNodeWithText("Undo").performClick()
        assertEquals(listOf(true, false), stars)
        // A starred file offers to remove it.
        rule.onNodeWithText("Old bill.docx", substring = true).performTouchInput { longClick() }
        rule.onNodeWithText("Remove from Starred").assertExists()
    }

    @Test fun aRecordingIsNotUnderDocuments() {
        val note = hit(7, file("Voice note.m4a", Kind.AUDIO), chunkId = 70, locator = "0:12")
        fun render(hits: List<SearchEngine.Hit>) = rule.setContent {
            ShotTheme(false, 1f) { Pane(ready.answered("bill", hits), SearchActions()) }
        }
        render(listOf(note))
        rule.onNodeWithText("Recordings").assertExists()
        rule.onNodeWithText("Documents and more").assertDoesNotExist()
    }

    @Test fun resultsLessLikelyLight() {
        shot("results_less_likely", false)
        rule.onNodeWithText("Less likely matches", substring = true).assertExists()
        rule.onNodeWithText("Nothing matches", substring = true).assertDoesNotExist()
    }

    @Test fun resultsLessLikelyDark() = shot("results_less_likely", true)

    @Test fun resultsNothingWellLight() {
        shot("results_nothing_well", false)
        rule.onNodeWithText("Nothing matches “bill” well").assertDoesNotExist()
        rule.onNodeWithText("These are the closest files, but they may not be what you're looking for.").assertDoesNotExist()
        rule.onNodeWithText("Less likely matches", substring = true).assertExists()
    }

    @Test fun resultsNothingWellDark() = shot("results_nothing_well", true)

    @Test fun resultsFirstPartLight() {
        shot("results_first_part", false)
        rule.onAllNodesWithText(Words.extent(ReadExtent.FIRST_PART)!!).assertCountEquals(1)
        rule.onNodeWithText(Words.extent(ReadExtent.DEEPENING)!!).assertExists()
        rule.onNodeWithContentDescription(
            "Video, Birthday.mp4",
            substring = true,
        ).assertContentDescriptionContains(Words.extent(ReadExtent.FIRST_PART)!!, substring = true)
    }

    @Test fun resultsFirstPartDark() = shot("results_first_part", true)

    @Test fun allStrongHasNoLessLikelyHeader() {
        shot("results", false)
        rule.onNodeWithText("Less likely matches", substring = true).assertDoesNotExist()
    }

    @Test fun wordsOnlyPhoneSaysSo() {
        rule.setContent {
            ShotTheme(false, 1f) { Pane(ready.copy(vectorLane = false, textOnly = true).answered("bill", mixedHits), SearchActions()) }
        }
        // No "Words only" label (retired): nothing is broken, and the download hint stays for few results.
        rule.onNodeWithText("Words only").assertDoesNotExist()
        rule.onNodeWithText("back shortly", substring = true).assertDoesNotExist()
    }

    /** No model yet: no "Words only" label, why each result matched, and under few results one line to the upgrade. */
    @Test fun withoutTheModelResultsSayWordsOnlyAndOfferTheUpgrade() {
        val went = mutableListOf<Route>()
        val hits = listOf(
            SearchEngine.Hit(file("Water bill.pdf", Kind.PDF), 1, 0, "", null, 1.0, byName = true),
            SearchEngine.Hit(file("IMG_2041.jpg", Kind.PHOTO), 2, 0, "", null, 0.5, byName = false, matchReason = Words.matches(listOf("beach"))),
        )
        rule.setContent {
            ShotTheme(false, 1f) {
                Pane(
                    SearchUi(setUp = false, vectorLane = false, upgrade = UpgradeStage.OFFER).answered("bill", hits),
                    SearchActions(onNavigate = {
                        went += it
                    }),
                )
            }
        }
        rule.shot("search_words_only_light")
        rule.onNodeWithText("Words only").assertDoesNotExist()
        rule.onNodeWithText(Words.NAME_MATCHES, substring = true).assertExists()
        rule.onNodeWithText("Matches: beach").assertExists()
        rule.onNodeWithText(Words.upgradeHint(2, UpgradeStage.OFFER)!!).performClick()
        assertEquals(listOf<Route>(SettingsRoute), went)
    }

    /** With the model in place, no hint. */
    @Test fun noUpgradeHintWithTheModel() {
        rule.setContent { ShotTheme(false, 1f) { Pane(ready.copy(upgrade = UpgradeStage.DONE).answered("bill", mixedHits.take(1)), SearchActions()) } }
        rule.onNodeWithText("Download for meaning search").assertDoesNotExist()
    }

    @Test fun tappingTheSelectedKindChipClearsIt() {
        val chosen = mutableListOf<Kind?>()
        rule.setContent {
            ShotTheme(false, 1f) {
                Pane(ready.copy(chip = Kind.PDF).answered("bill", mixedHits.filter { it.file.kind == Kind.PDF }), SearchActions(onChip = { chosen += it }))
            }
        }
        rule.onNodeWithText("PDFs").performClick()
        rule.onNodeWithText("Photos").performClick()
        assertEquals(listOf(null, Kind.PHOTO), chosen)
    }

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

    @Test fun errorLight() {
        shot("error", false)
        rule.onNodeWithText(Words.SEARCH_FAILED).assertExists()
    }

    @Test fun errorDark() = shot("error", true)

    @Test fun anErrorStaysUpUntilItWasShown() {
        val text = "That didn't work. Try again."
        var ui by mutableStateOf(ready.copy(error = text))
        rule.setContent { ShotTheme(false, 1f) { Screen(ui, readyHome, IndexState(), SearchActions(onErrorShown = { ui = ui.copy(error = null) })) } }
        rule.waitForIdle()
        rule.onNodeWithText(text).assertExists()
    }

    @Test fun theSearchingDogWaitsBelowTheChipsAndNeverOverHits() {
        val searching = ready.copy(query = "bill", searching = true).let { it.copy(phase = SearchPhase.Searching(it.question)) }
        var ui by mutableStateOf(searching)
        rule.mainClock.autoAdvance = false
        rule.setContent { ShotTheme(false, 1f) { Pane(ui, SearchActions()) } }
        rule.mainClock.advanceTimeBy(200)
        rule.onNodeWithTag(SEARCHING_MASCOT_TAG).assertDoesNotExist()
        rule.mainClock.advanceTimeBy(400)
        val dog = rule.onNodeWithTag(SEARCHING_MASCOT_TAG).assertExists().getBoundsInRoot()
        assertTrue(dog.top >= rule.onNodeWithText("All").getBoundsInRoot().bottom)
        // The names found some: the hits show, and the dog goes.
        ui = searching.answered("bill", mixedHits, complete = false)
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithText("Water bill.pdf", substring = true).assertExists()
        rule.onNodeWithTag(SEARCHING_MASCOT_TAG).assertDoesNotExist()
    }
}
