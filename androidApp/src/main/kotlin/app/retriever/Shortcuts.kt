package app.retriever

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A search handed to the app from outside (a shortcut, or `am start --es query`); the search screen takes it and calls [consume]. */
object LaunchQuery {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending

    fun from(intent: Intent?) {
        intent?.getStringExtra(EXTRA)?.trim()?.takeIf { it.isNotEmpty() }?.let { _pending.value = it }
    }

    fun consume() {
        _pending.value = null
    }

    const val EXTRA = "query"
}

private const val MAX_RECENT = 3

/** Long-press shortcuts for the latest searches, newest first. Replaces the previous set. */
fun pushRecent(context: Context, queries: List<String>) {
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
