package app.rummage

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import app.rummage.core.Kind
import app.rummage.core.SourceFile

object Opener {
    fun open(context: Context, file: SourceFile, locator: String = "", toPlayer: (PlayerRoute) -> Unit) {
        val ms = locatorToMs(locator)
        if ((file.kind == Kind.AUDIO || file.kind == Kind.VIDEO) && ms != null) {
            toPlayer(PlayerRoute(file.uri, file.mime, ms, file.name))
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(file.uri), file.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app on this phone opens this file", Toast.LENGTH_SHORT).show()
        }
    }

    /** "m:ss" or "h:mm:ss" to milliseconds; null for anything else. */
    fun locatorToMs(locator: String): Long? {
        val parts = locator.split(':')
        if (parts.size !in 2..3 || parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
        val seconds = parts.fold(0L) { acc, p -> acc * 60 + (p.toLongOrNull() ?: return null) }
        return seconds * 1000
    }
}
