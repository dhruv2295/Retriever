package app.retriever.search

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.retriever.ShotTheme
import app.retriever.home.HomeUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SearchBackTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var back: OnBackPressedDispatcher
    private var query = ""

    private fun show(initial: String = "") {
        rule.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            ShotTheme(false) {
                SearchContent(SearchUi(query = initial), HomeUi(), SearchActions(onQuery = { query = it })) { _, _, _, _ -> }
            }
        }
    }

    @Test fun backClearsAQueryBeforeLeavingHome() {
        show("receipt")
        rule.runOnIdle {
            assertTrue(back.hasEnabledCallbacks())
            back.onBackPressed()
        }
        rule.waitForIdle()
        assertEquals("", query)
        rule.runOnIdle { assertFalse(back.hasEnabledCallbacks()) }
    }

    @Test fun backCollapsesAnEmptyExpandedSearch() {
        show()
        rule.onNodeWithText("Search inside your files").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithContentDescription("Back").onFirst().assertExists()
        rule.runOnIdle { back.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Back").assertDoesNotExist()
        rule.runOnIdle { assertFalse(back.hasEnabledCallbacks()) }
    }

    @Test fun idleHomeDoesNotInterceptBack() {
        show()
        rule.runOnIdle { assertFalse(back.hasEnabledCallbacks()) }
    }
}
