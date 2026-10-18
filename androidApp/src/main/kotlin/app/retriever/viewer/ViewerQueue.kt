package app.retriever.viewer

import app.retriever.core.Kind
import app.retriever.core.SourceFile
import app.retriever.nav.PhotoRoute

/** What the photo viewer needs of a file. */
data class PhotoItem(val uri: String, val name: String, val mime: String)

/**
 * The photos of the list a person opened one from, so the viewer can swipe through them. Held in memory only: after
 * process death the route's own photo is shown alone, which is why the screen reads [itemsFor] rather than the field.
 */
class ViewerQueue {
    @Volatile private var items: List<PhotoItem> = emptyList()

    fun set(files: List<SourceFile>) {
        items = files.filter { it.kind == Kind.PHOTO || it.kind == Kind.SCREENSHOT }.map { PhotoItem(it.uri, it.name, it.mime) }
    }

    /** The queue when it holds the opened photo; otherwise just that photo. */
    fun itemsFor(route: PhotoRoute): List<PhotoItem> =
        items.takeIf { q -> q.any { it.uri == route.uri } } ?: listOf(PhotoItem(route.uri, route.name, route.mime))
}
