package app.retriever.search

import android.content.pm.ShortcutManager
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ShortcutsTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun usedReportsOnlyAShortcutsOwnSearch() {
        val manager = shadowOf(context.getSystemService(ShortcutManager::class.java))
        val shortcuts = RecentShortcuts(context)
        shortcuts.push(listOf("bill"))
        shortcuts.used("something else")
        assertEquals(0, manager.reportedShortcutsUsed.count())
        shortcuts.used("bill")
        assertEquals(listOf("recent-0"), manager.reportedShortcutsUsed.toList())
    }

    @Test
    fun pushRecentPublishesAtMostThree() {
        RecentShortcuts(context).push(listOf("a", "b", "b", "c", "d", "e"))
        val ids = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.shortLabel.toString() }.sorted()
        assertEquals(listOf("a", "b", "c"), ids)
    }
}
