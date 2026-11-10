package app.retriever.search

import android.animation.ValueAnimator
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.platform.app.InstrumentationRegistry
import app.retriever.ShotTheme
import app.retriever.core.Words
import app.retriever.home.HomeUi
import app.retriever.nav.SearchRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Search is a screen like any other: Back belongs to the nav host, and the keyboard comes up only to type. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SearchBackTest {
    @get:Rule val rule = createComposeRule()
    private var back: OnBackPressedDispatcher? = null

    // A phone in use is in touch mode; out of it, a window hands its first field the focus again.
    @Before fun touchMode() = InstrumentationRegistry.getInstrumentation().setInTouchMode(true)

    private fun search(query: String = "", typing: Boolean = query.isBlank(), onBack: () -> Unit = {}) {
        rule.setContent {
            back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            ShotTheme(false) { SearchContent(SearchUi(query = query), HomeUi(), SearchActions(), onBack, query = query, typing = typing) }
        }
    }

    private fun keyboardUp() = shadowOf(RuntimeEnvironment.getApplication().getSystemService(InputMethodManager::class.java)).isSoftInputVisible

    /** One Back is one screen, from the results as from a viewer: nothing here holds it. */
    @Test fun theSearchScreenNeverHoldsBack() {
        search("receipt", typing = false)
        rule.runOnIdle { assertEquals(false, back?.hasEnabledCallbacks()) }
    }

    @Test fun theBackArrowLeaves() {
        var left = 0
        search(onBack = { left++ })
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, left)
    }

    @Test fun anEmptySearchOpensReadyToType() {
        search()
        rule.onAllNodes(isFocused()).assertCountEquals(1)
    }

    /** A chip's or a recent search opens on its results: frame by frame, the field never takes focus or the keyboard. */
    @Test fun aChipsSearchNeverFocusesTheField() {
        // The mascot stays still, as with reduced motion: Robolectric decodes its clips as stills, not animations.
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.java).invoke(null, 0f)
        search("invoice", typing = false)
        rule.onAllNodes(isFocused()).assertCountEquals(0)
        rule.mainClock.autoAdvance = false
        repeat(60) {
            rule.mainClock.advanceTimeByFrame()
            rule.onAllNodes(isFocused()).assertCountEquals(0)
        }
        assertFalse(keyboardUp())
    }

    @Test fun aTapFocusesTheFieldAfterAChipsSearch() {
        search("invoice", typing = false)
        rule.onAllNodes(hasSetTextAction())[0].performClick()
        rule.onAllNodes(isFocused()).assertCountEquals(1)
    }

    /** TalkBack's double-tap sends no touch: it runs the field's click action, which must focus it too. */
    @Test fun aScreenReaderClickFocusesTheFieldAfterAChipsSearch() {
        search("invoice", typing = false)
        rule.onAllNodes(hasSetTextAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        rule.onAllNodes(isFocused()).assertCountEquals(1)
    }

    /** Back from a viewer rebuilds the screen from its saved state: the keyboard asked for on the way in stays down. */
    @Test fun comingBackFromAViewerBringsNoKeyboard() {
        val restore = StateRestorationTester(rule)
        restore.setContent { ShotTheme(false) { SearchContent(SearchUi(), HomeUi(), SearchActions(), onBack = {}) } }
        rule.onAllNodes(isFocused()).assertCountEquals(1)
        restore.emulateSavedInstanceStateRestore()
        rule.onAllNodes(isFocused()).assertCountEquals(0)
    }

    @Test fun homeHoldsNoBackAndItsPillOpensSearch() {
        var opened: SearchRoute? = null
        rule.setContent {
            back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            ShotTheme(false) { HomeShell(SearchUi(), SearchActions(), open = { opened = it }) { _, _ -> } }
        }
        rule.runOnIdle { assertEquals(false, back?.hasEnabledCallbacks()) }
        rule.onNodeWithText(Words.SEARCH_FIELD).performClick()
        assertEquals(SearchRoute(), opened)
    }
}
