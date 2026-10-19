package app.retriever.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import app.retriever.ShotTheme
import app.retriever.core.Kind
import app.retriever.core.SourceFile
import app.retriever.shot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class TextThumbnailTest {
    @get:Rule val rule = createComposeRule()

    @Test fun eachTileRequestsItsOwnContent() {
        val names = listOf("Lease.docx", "Notes.txt", "Budget.xlsx")
        val requested = mutableListOf<String>()
        rule.setContent {
            ShotTheme(false) {
                Row {
                    names.forEach { name ->
                        Thumb(SourceFile("file:///$name", name, "", "text/plain", Kind.DOC, 1, 1), 110.dp, preview = { file ->
                            requested += file.name
                            "${file.name}: The security deposit is refundable."
                        })
                    }
                }
            }
        }
        rule.waitForIdle()
        assertEquals(names, requested)
        rule.shot("document_text_tiles")
    }
}
