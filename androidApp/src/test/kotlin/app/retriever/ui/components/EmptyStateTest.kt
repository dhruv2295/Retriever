package app.retriever.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import app.retriever.R
import app.retriever.ShotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class EmptyStateTest {
    @get:Rule val rule = createComposeRule()

    @Test fun recoveryRemainsReachableInAShortWindowWithLargeText() {
        var opened = 0
        rule.setContent {
            ShotTheme(false, fontScale = 2f) {
                Box(Modifier.size(360.dp, 180.dp), contentAlignment = Alignment.Center) {
                    EmptyState(
                        "Couldn't open this file",
                        "It may have been moved or deleted. Open it in another app to see it now.",
                        R.drawable.ic_warning_amber,
                        action = "Open in another app" to { opened++ },
                    )
                }
            }
        }
        rule.onNodeWithText("Open in another app").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, opened)
        rule.onNodeWithText("Couldn't open this file").performScrollTo().assertIsDisplayed()
    }
}
