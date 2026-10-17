package app.retriever

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import app.retriever.core.Kind
import app.retriever.core.SourceFile

object Opener {
    fun open(context: Context, file: SourceFile, locator: String = "", navigate: (Any) -> Unit) {
        routeFor(file, locator)?.let { navigate(it); return }
        view(context, file.uri, file.mime)
    }

    /** Hands the file to whichever app opens it. */
    fun view(context: Context, uri: String, mime: String) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(uri), mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app on this phone opens this file", Toast.LENGTH_SHORT).show()
        }
    }

    /** The in-app screen that shows the match itself, or null when another app should open the file. */
    fun routeFor(file: SourceFile, locator: String): Any? {
        val page = locatorToPage(locator)
        val ms = locatorToMs(locator)
        return when {
            file.kind == Kind.PDF && page != null -> PdfRoute(file.uri, page, file.name)
            (file.kind == Kind.AUDIO || file.kind == Kind.VIDEO) && ms != null -> PlayerRoute(file.uri, file.mime, ms, file.name)
            else -> null
        }
    }

    /** "m:ss" or "h:mm:ss" to milliseconds; null for anything else. */
    fun locatorToMs(locator: String): Long? {
        val parts = locator.split(':')
        if (parts.size !in 2..3 || parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
        val seconds = parts.fold(0L) { acc, p -> acc * 60 + (p.toLongOrNull() ?: return null) }
        return seconds * 1000
    }

    /** "p. 12" to 12; null for anything else. */
    fun locatorToPage(locator: String): Int? =
        locator.removePrefix("p. ").takeIf { it.length < locator.length && it.all(Char::isDigit) }?.toIntOrNull()?.takeIf { it >= 1 }
}
