package app.retriever.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ScrollingEffectTest {
    @get:Rule val rule = createComposeRule()

    @Test fun stoppedScreensReleaseScrollingAndIgnoreChangesUntilStartedAgain() {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this)
        }
        val scrolling = mutableStateOf(false)
        val reports = mutableListOf<Boolean>()
        rule.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.STARTED }
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                ScrollingEffect({ scrolling.value }, reports::add)
            }
        }
        rule.runOnIdle { scrolling.value = true }
        rule.runOnIdle { assertEquals(listOf(false, true), reports) }
        rule.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.CREATED }
        rule.runOnIdle { assertEquals(false, reports.last()) }
        rule.runOnIdle { scrolling.value = false }
        rule.runOnIdle { scrolling.value = true }
        rule.runOnIdle { assertEquals(listOf(false, true, false), reports) }
        rule.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.STARTED }
        rule.runOnIdle { assertEquals(true, reports.last()) }
        rule.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.DESTROYED }
        rule.runOnIdle { assertEquals(false, reports.last()) }
    }
}
