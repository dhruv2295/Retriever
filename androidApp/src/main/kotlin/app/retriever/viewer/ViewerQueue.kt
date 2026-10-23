package app.retriever.viewer

import app.retriever.core.Kind
import app.retriever.core.SourceFile
import app.retriever.nav.PhotoRoute

/** What the photo viewer needs of a file; [file] when the list had it, for its picture in memory (none after a restore). */
data class PhotoItem(val uri: String, val name: String, val mime: String, val file: SourceFile? = null)

/**
 * The list a person opened a file from, so the viewers can swipe through its photos and step to its previous and next
 * file. Held in memory only: after process death the route's own photo is shown alone, which is why the screens read
 * [itemsFor] and [neighbors] rather than the field.
 */
class ViewerQueue {
    @Volatile private var files: List<SourceFile> = emptyList()

    fun set(files: List<SourceFile>) {
        this.files = files
    }

    /** The queue's photos when they hold the opened one; otherwise just that photo. */
    fun itemsFor(route: PhotoRoute): List<PhotoItem> =
        files.filter { it.kind == Kind.PHOTO || it.kind == Kind.SCREENSHOT }.map { PhotoItem(it.uri, it.name, it.mime, it) }
            .takeIf { q -> q.any { it.uri == route.uri } } ?: listOf(PhotoItem(route.uri, route.name, route.mime))

    /**
     * The files before and after [uri] that open in the same kind of viewer, and "2 of 5" for its place among them; empty
     * when the list does not hold it. A PDF steps to PDFs, a recording or video to its own kind, never to a photo.
     */
    fun neighbors(uri: String): Neighbors {
        val kind = files.firstOrNull { it.uri == uri }?.kind?.viewer() ?: return Neighbors(null, null)
        val same = files.filter { it.kind.viewer() == kind }
        val at = same.indexOfFirst { it.uri == uri }
        return Neighbors(same.getOrNull(at - 1).takeIf { at > 0 }, same.getOrNull(at + 1), positionLabel(at, same.size))
    }
}

private fun Kind.viewer() = when (this) {
    Kind.AUDIO, Kind.VIDEO -> Kind.VIDEO
    Kind.PHOTO, Kind.SCREENSHOT -> Kind.PHOTO
    Kind.OTHER -> null
    else -> this
}

class Neighbors(val previous: SourceFile?, val next: SourceFile?, val label: String? = null)

/** "3 of 12" for the photo on screen; null when the list holds only this one, so a lone photo shows no swipe cue. */
internal fun positionLabel(index: Int, count: Int): String? = if (count > 1) "${index + 1} of $count" else null
