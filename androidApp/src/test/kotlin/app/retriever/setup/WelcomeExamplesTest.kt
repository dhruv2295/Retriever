package app.retriever.setup

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import app.retriever.core.Words
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WelcomeExamplesTest {
    @get:Rule val rule = createComposeRule()

    private val examples = Words.HELLO_EXAMPLES

    private fun typed(query: String) = rule.onNodeWithText(query).fetchSemanticsNode().config[SemanticsProperties.Text].single()

    /** Remove animations: the first example simply shows, whole, with its card. */
    @Test fun withMotionOffTheFirstExampleShowsWhole() {
        val animate = mutableStateOf(false)
        rule.mainClock.autoAdvance = false
        rule.setContent { MaterialTheme { HelloExamples(textOnly = false, animate = animate.value) } }
        rule.mainClock.advanceTimeByFrame()
        animate.value = true
        rule.mainClock.advanceTimeBy(300)
        animate.value = false
        rule.mainClock.advanceTimeByFrame()
        val text = typed(examples[0].query)
        assertEquals(examples[0].query, text.text)
        assertTrue(text.spanStyles.none { it.item.color == Color.Transparent })
        rule.onNodeWithText(examples[0].title).assertExists()
        rule.onNodeWithText(examples[0].detail).assertExists()
    }

    /** The query types in with its whole text laid out (the tail transparent), then the next example follows. */
    @Test fun theExamplesTypeInAndLoop() {
        rule.mainClock.autoAdvance = false
        rule.setContent { MaterialTheme { HelloExamples(textOnly = false, animate = true) } }
        rule.mainClock.advanceTimeBy(300)
        val text = typed(examples[0].query)
        assertEquals(examples[0].query, text.text)
        assertTrue(text.spanStyles.any { it.item.color == Color.Transparent })
        rule.mainClock.advanceTimeUntil(3 * EXAMPLE_MS) { shows(examples[1].title) }
    }

    private fun shows(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** Without search by description the photo found by what's in it would be a false promise. */
    @Test fun textOnlyOmitsThePhotoExample() {
        rule.mainClock.autoAdvance = false
        rule.setContent { MaterialTheme { HelloExamples(textOnly = true, animate = true) } }
        rule.mainClock.advanceTimeUntil(3 * EXAMPLE_MS) { shows(examples[2].title) }
        rule.onNodeWithText(examples[1].title).assertDoesNotExist()
    }
}
