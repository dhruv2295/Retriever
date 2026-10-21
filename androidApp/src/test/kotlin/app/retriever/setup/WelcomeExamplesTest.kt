package app.retriever.setup

import android.animation.ValueAnimator
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WelcomeExamplesTest {
    @get:Rule val rule = createComposeRule()

    private val queries =
        listOf("“sunset at the beach”", "“the photo of the medicine box”", "“the Wi-Fi password screenshot”", "“lease that mentions the deposit”")

    @Test fun welcomeExposesFullExamplesImmediatelyWithMotionOff() {
        ReflectionHelpers.callStaticMethod<Void>(
            ValueAnimator::class.java,
            "setDurationScale",
            ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, 0f),
        )
        try {
            assertFalse(ValueAnimator.areAnimatorsEnabled())
            rule.mainClock.autoAdvance = false
            rule.setContent { MaterialTheme { Column { Welcome(SetupUi(), actions = SetupActions()) } } }
            queries.forEach { query ->
                val text = rule.onNodeWithText(query).fetchSemanticsNode().config[SemanticsProperties.Text].single()
                assertEquals(query, text.text)
                assertTrue(text.spanStyles.none { it.item.color == Color.Transparent })
            }
        } finally {
            ReflectionHelpers.callStaticMethod<Void>(
                ValueAnimator::class.java,
                "setDurationScale",
                ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, 1f),
            )
        }
    }

    @Test fun typingKeepsFullSemanticsAndDoesNotReplayAfterRestore() {
        rule.mainClock.autoAdvance = false
        val restoration = StateRestorationTester(rule)
        restoration.setContent { MaterialTheme { WelcomeExamples(textOnly = false) } }
        rule.mainClock.advanceTimeByFrame()
        queries.forEach { query ->
            val text = rule.onNodeWithText(query).fetchSemanticsNode().config[SemanticsProperties.Text].single()
            assertEquals(query, text.text)
            assertTrue(text.spanStyles.any { it.item.color == Color.Transparent })
        }
        // The helper must be able to compose its disposal and restoration as separate frames.
        rule.mainClock.autoAdvance = true
        restoration.emulateSavedInstanceStateRestore()
        queries.forEach { query ->
            val text = rule.onNodeWithText(query).fetchSemanticsNode().config[SemanticsProperties.Text].single()
            assertTrue(text.spanStyles.none { it.item.color == Color.Transparent })
        }
    }

    @Test fun textOnlyOmitsThePhotoExample() {
        rule.setContent { MaterialTheme { WelcomeExamples(textOnly = true) } }
        rule.onNodeWithText(queries.first()).assertDoesNotExist()
        queries.drop(1).forEach { rule.onNodeWithText(it).assertExists() }
    }
}
