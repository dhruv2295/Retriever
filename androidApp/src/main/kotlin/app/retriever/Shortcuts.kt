package app.retriever

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A search handed to the app from outside (a shortcut, or `am start --es query`); the search screen takes it and calls [consume]. */
class LaunchQuery {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun from(intent: Intent?) {
        intent?.getStringExtra(EXTRA)?.trim()?.takeIf { it.isNotEmpty() }?.let { _pending.value = it }
    }

    fun consume() {
        _pending.value = null
    }

    companion object {
        const val EXTRA = "query"
    }
}

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
                    Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(LaunchQuery.EXTRA, q),
                )
                .build()
        }.toList()
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }

    /** Tells the launcher this shortcut's search ran, so it ranks it up. A search that did not come from a shortcut matches none. */
    fun used(query: String) {
        ShortcutManagerCompat.getDynamicShortcuts(context)
            .firstOrNull { it.intent.getStringExtra(LaunchQuery.EXTRA) == query }
            ?.let { ShortcutManagerCompat.reportShortcutUsed(context, it.id) }
    }
}
