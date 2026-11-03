package app.retriever.search

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.retriever.MainActivity
import app.retriever.R

private const val MAX_RECENT = 3

/** Long-press shortcuts for the latest searches. */
class RecentShortcuts(private val context: Context) {
    /** Newest first; replaces the previous set. */
    fun push(queries: List<String>) {
        val shortcuts = queries.asSequence().map(String::trim).filter { it.isNotEmpty() }.distinct().take(MAX_RECENT).mapIndexed { rank, q ->
            ShortcutInfoCompat.Builder(context, "recent-$rank")
                .setShortLabel(q)
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setRank(rank)
                .setIntent(
                    Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(EXTRA, q),
                )
                .build()
        }.toList()
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }

    /** Tells the launcher this shortcut's search ran, so it ranks it up. A search that did not come from a shortcut matches none. */
    fun used(query: String) {
        ShortcutManagerCompat.getDynamicShortcuts(context)
            .firstOrNull { it.intent.getStringExtra(EXTRA) == query }
            ?.let { ShortcutManagerCompat.reportShortcutUsed(context, it.id) }
    }

    companion object {
        /** The intent extra holding a shortcut's words; `am start --es query` uses it too. */
        const val EXTRA = "query"
    }
}
