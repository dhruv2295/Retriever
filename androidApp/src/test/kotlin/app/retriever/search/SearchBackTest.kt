package app.retriever.search

import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import app.retriever.ShotTheme
import app.retriever.home.HomeUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
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

    /**
     * A chip's search (Home's, Try's) opens its results with nothing to type: the field holds no focus, which with the
     * keyboard down would take the first Back, so one Back leaves the results.
     */
    @Test fun oneBackLeavesAChipsResults() {
        // A phone in use is in touch mode; out of it, a window hands its first field the focus again.
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        var fill: ((String, Boolean) -> Unit)? = null
        rule.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            ShotTheme(false) {
                SearchContent(SearchUi(), HomeUi(), SearchActions(onQuery = { query = it })) { _, _, _, f -> fill = f }
            }
        }
        rule.runOnIdle { fill!!("invoice", true) }
        rule.waitForIdle()
        rule.onAllNodesWithContentDescription("Back").onFirst().assertExists()
        rule.onAllNodes(isFocused()).assertCountEquals(0)
        rule.runOnIdle { back.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Back").assertDoesNotExist()
        assertEquals("", query)
        rule.runOnIdle { assertFalse(back.hasEnabledCallbacks()) }
    }

    /**
     * The phone's bug: M3 asks the opening bar's field for focus, and a give-back after idle passed here while the
     * keyboard stayed up on the device. Frame by frame, the field must never hold focus while the bar opens.
     */
    @Test fun aChipsSearchNeverFocusesTheField() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        var fill: ((String, Boolean) -> Unit)? = null
        rule.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            ShotTheme(false) {
                SearchContent(SearchUi(), HomeUi(), SearchActions(onQuery = { query = it })) { _, _, _, f -> fill = f }
            }
        }
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { fill!!("invoice", true) }
        repeat(60) {
            rule.mainClock.advanceTimeByFrame()
            rule.onAllNodes(isFocused()).assertCountEquals(0)
        }
        rule.onAllNodesWithContentDescription("Back").onFirst().assertExists()
        val ime = RuntimeEnvironment.getApplication().getSystemService(InputMethodManager::class.java)
        assertFalse(shadowOf(ime).isSoftInputVisible)
        rule.mainClock.autoAdvance = true
        rule.runOnIdle { back.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    /** Once the bar has settled open, a tap on the field focuses it as usual. */
    @Test fun aTapFocusesTheFieldAfterAChipsSearch() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        var fill: ((String, Boolean) -> Unit)? = null
        rule.setContent {
            ShotTheme(false) {
                SearchContent(SearchUi(), HomeUi(), SearchActions(onQuery = { query = it })) { _, _, _, f -> fill = f }
            }
        }
        rule.runOnIdle { fill!!("invoice", true) }
        rule.waitForIdle()
        rule.onAllNodes(hasSetTextAction()).onLast().performClick()
        rule.onAllNodes(isFocused()).assertCountEquals(1)
    }

    @Test fun idleHomeDoesNotInterceptBack() {
        show()
        rule.runOnIdle { assertFalse(back.hasEnabledCallbacks()) }
    }
}
