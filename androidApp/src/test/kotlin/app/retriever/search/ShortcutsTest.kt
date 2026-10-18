package app.retriever.search

import android.content.Intent
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ShortcutsTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun queryExtraReachesLaunchQuery() {
        val launch = LaunchQuery()
        launch.from(Intent().putExtra("query", "  boarding pass "))
        assertEquals("boarding pass", launch.pending.value)
        launch.consume()
        assertNull(launch.pending.value)
        launch.from(Intent().putExtra("query", "  "))
        assertNull(launch.pending.value)
    }

    @Test
    fun pushRecentPublishesAtMostThree() {
        RecentShortcuts(context).push(listOf("a", "b", "b", "c", "d", "e"))
        val ids = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.shortLabel.toString() }.sorted()
        assertEquals(listOf("a", "b", "c"), ids)
    }
}
