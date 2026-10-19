package app.retriever.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.retriever.ShotTheme
import app.retriever.core.IndexState
import app.retriever.core.Kind
import app.retriever.core.StopReason
import app.retriever.nav.BrowseRoute
import app.retriever.nav.Route
import app.retriever.search.SearchActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class LibraryHeroTest {
    @get:Rule val rule = createComposeRule()
    private val ready = HomeUi(libraryChosen = true, counts = mapOf(Kind.DOC to 10L), contentRead = 10, readByKind = mapOf(Kind.DOC to 10L))
    private var dismissed = false
    private var destination: Route? = null

    private fun show(ui: HomeUi, index: IndexState = IndexState(lastStop = StopReason.DONE)) {
        rule.setContent {
            ShotTheme(false) {
                HomeBody(
                    ui, index, false, emptyList(),
                    SearchActions(onNavigate = {
                        destination = it
                    }),
                    {}, {}, rememberLazyStaggeredGridState(), PaddingValues(), 0.dp, { _, _ -> }, onDismissCaughtUp = {
                        dismissed =
                            true
                    },
                )
            }
        }
    }

    @Test fun readingShowsProgressAgainstWholeLibrary() {
        show(
            ready.copy(waiting = 6, contentRead = 4, readByKind = mapOf(Kind.DOC to 4L)),
            IndexState(running = true, pendingAtStart = 6, currentFile = app.retriever.core.SourceFile("demo:a", "a", "", "text/plain", Kind.DOC, 1, 1)),
        )
        rule.onNodeWithText("4 files read").assertExists()
        rule.onNodeWithText("Reading your files").assertIsDisplayed()
    }

    @Test fun trialKeepsWholeLibraryTotalAndOnlyCountsReadFiles() {
        show(ready.copy(trial = true, waiting = 2, rest = 6, contentRead = 2, readByKind = mapOf(Kind.DOC to 2L)))
        rule.onNodeWithText("2 files read").assertIsDisplayed()
        rule.onNodeWithText("10 files in your library").assertExists()
    }

    @Test fun caughtUpHasAnAccessibleDismissControl() {
        show(ready)
        rule.onNodeWithContentDescription("Dismiss reading status").performClick()
        assertTrue(dismissed)
    }

    @Test fun dismissedHidesTheRow() {
        show(ready.copy(caughtUpDismissed = true))
        rule.onNodeWithContentDescription("Dismiss reading status").assertDoesNotExist()
        rule.onNodeWithText("Your files").assertIsDisplayed()
    }

    @Test fun textOnlyPhoneShowsItsLibraryAndReadCount() {
        show(ready.copy(inside = setOf(Kind.DOC, Kind.PDF, Kind.SCREENSHOT)))
        rule.onNodeWithText("10 files in your library").assertExists()
        rule.onNodeWithText("10 files read").assertExists()
    }

    @Test fun subtitleFollowsTheActualReadingState() {
        val idle = IndexState()
        assertTrue(homeStatus(ready.copy(trial = true, waiting = 2), idle)!!.title.startsWith("Waiting for Android"))
        assertTrue(homeStatus(ready.copy(trial = true, rest = 5), idle)!!.title.startsWith("Your sample is ready"))
        assertEquals("Reading paused", homeStatus(ready.copy(paused = true), idle)?.title)
        assertEquals("Starting reading…", homeStatus(ready.copy(waiting = 5), idle.copy(running = true, pendingAtStart = 5))?.title)
        assertEquals("Up to date", homeStatus(ready, idle)?.title)
    }

    @Test fun heroStaysCompactAtDefaultTextSize() {
        show(ready)
        val hero = rule.onNodeWithTag("library-hero").fetchSemanticsNode().boundsInRoot
        val screen = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(hero.height <= screen.height / 3)
    }
}
