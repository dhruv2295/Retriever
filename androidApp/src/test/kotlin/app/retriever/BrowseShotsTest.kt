package app.retriever

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import app.retriever.core.Kind
import app.retriever.core.SourceFile
import app.retriever.core.Store
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowseShotsTest {
    @get:Rule val rule = createComposeRule()

    private fun stored(i: Int, kind: Kind, ext: String) = Store.StoredFile(
        id = i.toLong(),
        file = SourceFile("demo:$i", "Item $i.$ext", "Camera", "application/octet-stream", kind, 1_200_000L * i, 1_760_000_000_000 - i * 86_400_000L),
        attempts = 0,
    )

    private fun content(files: List<Store.StoredFile>, kind: Kind?, dark: Boolean, loading: Boolean = false) {
        rule.setContent {
            ShotTheme(dark) {
                BrowseContent(
                    files = files, sort = "NEWEST", loading = loading, kind = kind,
                    padding = PaddingValues(), onSort = {}, onLoadMore = {}, onOpen = {},
                )
            }
        }
    }

    @Test fun photoGridIsThumbCells() {
        val files = List(12) { stored(it, Kind.PHOTO, "jpg") }
        content(files, Kind.PHOTO, dark = false)
        rule.onNodeWithText("Newest").assertIsSelected()
        rule.onNodeWithContentDescription("Photo, Item 0.jpg, ${Format.date(files[0].file.modifiedAt)}").assertExists()
        rule.onNodeWithText("Item 0.jpg").assertDoesNotExist()
        rule.shot("browse_photos_light")
    }

    @Test fun photoGridDark() {
        content(List(12) { stored(it, Kind.PHOTO, "jpg") }, Kind.PHOTO, dark = true)
        rule.shot("browse_photos_dark")
    }

    @Test fun documentsAreRows() {
        content(List(6) { stored(it, Kind.DOC, "docx") }, Kind.DOC, dark = false)
        rule.onNodeWithText("Item 0.docx").assertExists()
        rule.shot("browse_docs_light")
    }

    @Test fun documentsDark() {
        content(List(6) { stored(it, Kind.DOC, "docx") }, Kind.DOC, dark = true)
        rule.shot("browse_docs_dark")
    }

    @Test fun emptyLight() {
        content(emptyList(), Kind.PHOTO, dark = false)
        rule.onNodeWithText("Nothing here yet").assertExists()
        rule.shot("browse_empty_light")
    }

    @Test fun emptyDark() {
        content(emptyList(), null, dark = true)
        rule.shot("browse_empty_dark")
    }
}
