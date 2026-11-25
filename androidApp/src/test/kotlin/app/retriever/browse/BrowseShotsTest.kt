package app.retriever.browse

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.retriever.ShotTheme
import app.retriever.core.FileState
import app.retriever.core.FolderNode
import app.retriever.core.Kind
import app.retriever.core.SourceFile
import app.retriever.core.Store
import app.retriever.nav.BrowseRoute
import app.retriever.shot
import app.retriever.ui.Format
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowseShotsTest {
    @get:Rule val rule = createComposeRule()

    private fun stored(i: Int, kind: Kind, ext: String) = Store.StoredFile(
        id = i.toLong(),
        file = SourceFile("demo:$i", "Item $i.$ext", "Camera", "application/octet-stream", kind, 1_200_000L * i, 1_760_000_000_000 - i * 86_400_000L),
        attempts = 0,
        state = FileState.INDEXED,
        contentRead = true,
    )

    private fun content(
        files: List<Store.StoredFile>,
        kind: Kind?,
        dark: Boolean,
        loading: Boolean = false,
        starredOnly: Boolean = false,
        filtered: Boolean = false,
    ) {
        rule.setContent {
            ShotTheme(dark) {
                BrowseContent(
                    ui = BrowseUi(files = files, loading = loading),
                    kind = kind,
                    padding = PaddingValues(),
                    onSort = {},
                    onLoadMore = {},
                    onOpen = {},
                    starredOnly = starredOnly,
                    filtered = filtered,
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
        content(emptyList(), Kind.PHOTO, dark = false, filtered = true)
        rule.onNodeWithText("Nothing here yet").assertExists()
        rule.shot("browse_empty_light")
    }

    @Test fun starredListIsRowsWithoutSortChips() {
        content(List(4) { stored(it, Kind.PDF, "pdf") }, null, dark = false, starredOnly = true)
        rule.onNodeWithText("Item 0.pdf").assertExists()
        rule.onNodeWithText("Newest").assertDoesNotExist()
        rule.shot("browse_starred_light")
    }

    @Test fun folderOfFoldersIsNotEmpty() {
        rule.setContent {
            ShotTheme(false) {
                BrowseContent(
                    ui = BrowseUi(loading = false),
                    kind = null,
                    padding = PaddingValues(),
                    onSort = {},
                    onLoadMore = {},
                    onOpen = {},
                    filtered = true,
                    folder = "Android/media",
                    subfolders = listOf(FolderNode("Android/media/com.whatsapp/WhatsApp/Media", "com.whatsapp/WhatsApp/Media", 9)),
                )
            }
        }
        rule.onNodeWithText("com.whatsapp/WhatsApp/Media").assertExists()
        rule.onNodeWithText("Nothing here yet").assertDoesNotExist()
    }

    @Test fun starredEmptyState() {
        content(emptyList(), null, dark = false, starredOnly = true)
        rule.onNodeWithText("Keep useful files close").assertExists()
        rule.onNodeWithText("Tap the star on a file to find it here.").assertExists()
        rule.shot("browse_starred_empty_light")
    }

    @Test fun failedFirstPageOffersTryAgain() {
        var retried = 0
        rule.setContent {
            ShotTheme(false) {
                BrowseContent(ui = BrowseUi(loading = false, error = "x"), kind = Kind.DOC, padding = PaddingValues(), onSort = {
                }, onLoadMore = {}, onOpen = {}, onRetry = { retried++ })
            }
        }
        rule.onNodeWithText("Nothing here yet").assertDoesNotExist()
        rule.onNodeWithText("Try again").performClick()
        org.junit.Assert.assertEquals(1, retried)
    }

    @Test fun coverageLinePerRow() {
        val files = listOf(
            stored(1, Kind.DOC, "docx").copy(state = FileState.SKIPPED, contentRead = false),
            stored(2, Kind.DOC, "docx").copy(state = FileState.INDEXED, partial = true),
        )
        content(files, Kind.DOC, dark = false)
        rule.onNodeWithText("Couldn’t process").assertExists()
        rule.onNodeWithText("Processed in part · only its beginning").assertExists()
    }

    @Test fun titles() {
        org.junit.Assert.assertEquals("Starred", browseTitle(BrowseRoute(title = "Starred", starredOnly = true), "NEWEST", allFiles = true))
        org.junit.Assert.assertEquals("All files", browseTitle(BrowseRoute(sort = "LARGEST", title = "Large files"), "NEWEST", allFiles = true))
        org.junit.Assert.assertEquals("Large files", browseTitle(BrowseRoute(sort = "LARGEST", title = "Large files"), "LARGEST", allFiles = true))
    }

    @Test fun limitedAccessNeverCallsTheListAllFiles() {
        val everything = BrowseRoute(sort = "LARGEST", title = "Large files")
        org.junit.Assert.assertEquals("Files Retriever can access", browseTitle(everything, "NEWEST", allFiles = false))
        org.junit.Assert.assertEquals("Large files", browseTitle(everything, "LARGEST", allFiles = false))
        org.junit.Assert.assertEquals("Starred", browseTitle(BrowseRoute(title = "Starred", starredOnly = true), "NEWEST", allFiles = false))
    }

    @Test fun theSortChipsSayWhatTheyOrderBy() {
        content(listOf(stored(1, Kind.DOC, "docx")), Kind.DOC, dark = false)
        rule.onNodeWithText("Newest first · by date modified").assertExists()
        org.junit.Assert.assertEquals("Largest first", sortCaption("LARGEST"))
        org.junit.Assert.assertEquals("By name, A to Z", sortCaption("NAME"))
    }

    @Test fun anEmptyLibraryInvitesAndAFilteredEmptyOneStaysQuiet() {
        var managed = 0
        rule.setContent {
            ShotTheme(false) {
                BrowseContent(ui = BrowseUi(loading = false), kind = null, padding = PaddingValues(), onSort = {
                }, onLoadMore = {}, onOpen = {}, onManageAccess = { managed++ })
            }
        }
        rule.onNodeWithText("Choose files to search").assertExists()
        rule.onNodeWithText("Add photos or a folder.").assertExists()
        rule.onNodeWithText("Manage access").performClick()
        org.junit.Assert.assertEquals(1, managed)
        rule.onNodeWithText("Nothing here yet").assertDoesNotExist()
    }

    @Test fun aFilteredListThatIsEmptyIsNotAnEmptyLibrary() {
        rule.setContent {
            ShotTheme(false) {
                BrowseContent(ui = BrowseUi(loading = false), kind = Kind.PDF, padding = PaddingValues(), onSort = {
                }, onLoadMore = {}, onOpen = {}, filtered = true)
            }
        }
        rule.onNodeWithText("Nothing here yet").assertExists()
        rule.onNodeWithText("Choose files to search").assertDoesNotExist()
    }

    @Test fun emptyDark() {
        content(emptyList(), null, dark = true)
        rule.shot("browse_empty_dark")
    }
}
