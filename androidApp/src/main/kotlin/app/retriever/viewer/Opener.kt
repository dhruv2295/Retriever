package app.retriever.viewer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import app.retriever.core.Kind
import app.retriever.core.Locator
import app.retriever.core.SourceFile
import app.retriever.core.Words
import app.retriever.nav.DocRoute
import app.retriever.nav.PdfRoute
import app.retriever.nav.PhotoRoute
import app.retriever.nav.PlayerRoute
import app.retriever.nav.Route
import org.koin.mp.KoinPlatform

object Opener {
    /**
     * [siblings]: the list the file was opened from; a photo's viewer swipes through its photos and the others step to its neighbours. Empty keeps the last list [queue]d.
     * [origin]: the tile list a photo was tapped in, which its viewer grows from (see [PhotoRoute.origin]).
     */
    fun open(
        context: Context,
        file: SourceFile,
        locator: String = "",
        navigate: (Route) -> Unit,
        fileId: Long = 0,
        query: String = "",
        siblings: List<SourceFile> = emptyList(),
        origin: String = "",
    ) {
        val route = routeFor(file, locator, fileId, query, origin)
        if (route == null) return view(context, file.uri, file.mime)
        if (siblings.isNotEmpty()) queue(siblings)
        navigate(route)
    }

    /** Hands the viewer the list a result was tapped in. Koin may be absent in a bare UI test. */
    fun queue(files: List<SourceFile>) {
        KoinPlatform.getKoinOrNull()?.get<ViewerQueue>()?.set(files)
    }

    /** Hands the file to whichever app opens it. */
    fun view(context: Context, uri: String, mime: String) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri.toUri(), mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // An Application context can only start an activity in a task of its own.
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, Words.strings.noAppOpensFile, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * The in-app screen for a file, at the match when there is one; null when another app should open it (files Retriever reads only by name). [fileId] 0 means the reader looks the file up.
     */
    fun routeFor(file: SourceFile, locator: String = "", fileId: Long = 0, query: String = "", origin: String = ""): Route? = when (file.kind) {
        Kind.PDF -> Locator.pageOf(locator).let { PdfRoute(file.uri, it ?: 1, file.name, matched = it != null, query = query) }
        Kind.AUDIO, Kind.VIDEO -> PlayerRoute(file.uri, file.mime, Locator.msOf(locator) ?: 0, file.name)
        Kind.DOC -> DocRoute(fileId, file.uri, file.name, file.mime, locator, query)
        Kind.PHOTO, Kind.SCREENSHOT -> PhotoRoute(file.uri, file.name, file.mime, origin)
        Kind.OTHER -> null
    }
}
