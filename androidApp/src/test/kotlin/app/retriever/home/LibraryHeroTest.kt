package app.retriever.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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
    private val ready = HomeUi(setUp = true, counts = mapOf(Kind.DOC to 10L), searchable = 10, readByKind = mapOf(Kind.DOC to 10L))
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
        show(ready.copy(waiting = 6, searchable = 4, readByKind = mapOf(Kind.DOC to 4L)), IndexState(running = true, pendingAtStart = 6))
        rule.onNodeWithContentDescription("Documents: 4 of 10 read").assertExists()
        rule.onNodeWithText("Reading your files").assertIsDisplayed()
    }

    @Test fun trialKeepsWholeLibraryTotalAndOnlyCountsReadFiles() {
        show(ready.copy(trial = true, waiting = 2, rest = 6, searchable = 2, readByKind = mapOf(Kind.DOC to 2L)))
        rule.onNodeWithText("Files in your whole library · trial reading").assertIsDisplayed()
        rule.onNodeWithContentDescription("Documents: 2 of 10 read").assertExists()
    }

    @Test fun caughtUpHasAnAccessibleDismissControl() {
        show(ready)
        rule.onNodeWithContentDescription("Dismiss all caught up").performClick()
        assertTrue(dismissed)
    }

    @Test fun dismissedHidesTheRow() {
        show(ready.copy(caughtUpDismissed = true))
        rule.onNodeWithText("All caught up").assertDoesNotExist()
        rule.onNodeWithText("Your library").assertIsDisplayed()
    }

    @Test fun textOnlyPhoneHasDocumentChipAndOpensBrowse() {
        show(ready.copy(inside = setOf(Kind.DOC, Kind.PDF, Kind.SCREENSHOT)))
        rule.onNodeWithText("Documents 10").performClick()
        assertEquals(BrowseRoute(kind = "DOC", title = "Documents"), destination)
    }
}
