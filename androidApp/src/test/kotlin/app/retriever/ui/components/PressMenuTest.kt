package app.retriever.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PressMenuTest {
    @get:Rule val rule = createComposeRule()

    /** On ChromeOS and in desktop windows a right click opens a file's menu, as a long press does, and doesn't open the file. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aRightClickOpensTheMenuWithoutClicking() {
        var clicks = 0
        rule.setContent {
            PressMenu(content = { open ->
                Box(Modifier.size(100.dp).testTag("row").combinedClickable(onLongClick = open, onClick = { clicks++ }))
            }) { dismiss -> DropdownMenuItem(text = { Text("Share") }, onClick = dismiss) }
        }
        rule.onNodeWithTag("row").performMouseInput { rightClick() }
        rule.onNodeWithText("Share").assertExists()
        assertEquals(0, clicks)
    }
}
