package app.retriever.viewer

import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import app.retriever.ShotTheme
import app.retriever.core.Kind
import app.retriever.core.SourceFile
import app.retriever.nav.PhotoRoute
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PhotoScreenTest {
    @get:Rule val rule = createComposeRule()

    private val files = (1..5).map { SourceFile("file:///missing/$it.jpg", "p$it.jpg", "DCIM", "image/jpeg", Kind.PHOTO, 1, 1) }
    private val trouble = rule.onAllNodesWithText("Couldn't show this photo")

    private fun show(start: Int): PagerState {
        lateinit var pager: PagerState
        rule.setContent {
            ShotTheme(false) {
                val items = files.map { PhotoItem(it.uri, it.name, it.mime) }
                pager = rememberPagerState(start) { items.size }
                PhotoPager(items, pager) {}
            }
        }
        return pager
    }

    @Test fun openingTheThirdOfFiveShowsPageThreeAndASwipeShowsPageFour() {
        val queue = ViewerQueue().apply { set(files) }
        val third = files[2]
        val route = PhotoRoute(third.uri, third.name, third.mime)
        val start = queue.itemsFor(route).indexOfFirst { it.uri == route.uri }
        val pager = show(start)
        rule.waitForIdle()
        assertEquals(2, pager.currentPage)
        rule.onRoot().performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertEquals(3, pager.currentPage)
    }

    @Test fun aMissingPhotoShowsTheTroubleState() {
        show(0)
        rule.waitUntil(5_000) { trouble.fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun aRestoredScreenWithoutTheListShowsTheOpenedPhotoAlone() {
        val queue = ViewerQueue()
        val route = PhotoRoute("file:///missing/9.jpg", "x.jpg", "image/jpeg")
        assertEquals(listOf(PhotoItem(route.uri, route.name, route.mime)), queue.itemsFor(route))
    }
}
