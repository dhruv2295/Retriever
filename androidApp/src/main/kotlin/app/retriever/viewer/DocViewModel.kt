package app.retriever.viewer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.retriever.core.ChunkText
import app.retriever.core.FileState
import app.retriever.core.Retriever
import app.retriever.core.Store
import app.retriever.core.nameMatchRanges
import app.retriever.nav.DocRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** What the reader shows: the text the index holds for a file, read once. */
sealed interface DocUi {
    data object Loading : DocUi

    /** [matchIndex]: the paragraph the hit is in, where the list opens; [matches]: every paragraph holding the [query]'s words. */
    data class Text(val paragraphs: List<String>, val matchIndex: Int, val query: String, val matches: List<Int> = emptyList()) : DocUi

    /** The file waits to be read; nothing is wrong with it. */
    data object NotReadYet : DocUi

    /** Read, and nothing came of it (names only, or no words in it). */
    data object Empty : DocUi

    /** The index has no such file, or reading it threw. */
    data object Failed : DocUi
}

class DocViewModel(handle: SavedStateHandle, private val retriever: Retriever, private val store: Store) : ViewModel() {
    val route: DocRoute = handle.toRoute()

    val ui: StateFlow<DocUi> = flow {
        emit(
            loadDoc(route.query, route.locator) {
                // A file opened from a list has no id yet; the index finds it by address.
                val file = withContext(Dispatchers.IO) { if (route.fileId != 0L) store.storedById(route.fileId) else store.fileByUri(route.uri) }
                file?.let { it.state to retriever.fileText(it.id) }
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DocUi.Loading)
}

/**
 * Reads the file's state and chunks with [read] (null: no such file) and picks the paragraph to open on. A missing file
 * or a read that throws is [DocUi.Failed].
 */
internal suspend fun loadDoc(query: String, locator: String, read: suspend () -> Pair<FileState, List<ChunkText>>?): DocUi {
    val (state, chunks) = try {
        read() ?: return DocUi.Failed
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return DocUi.Failed
    }
    if (chunks.isEmpty()) return if (state == FileState.PENDING) DocUi.NotReadYet else DocUi.Empty
    // The hit's own page or time first; within it, the paragraph that holds the query's words.
    val here = if (locator.isEmpty()) emptyList() else chunks.indices.filter { chunks[it].locator == locator }
    val pool = here.ifEmpty { chunks.indices.toList() }
    val match = pool.firstOrNull { nameMatchRanges(chunks[it].text, query).isNotEmpty() } ?: here.firstOrNull() ?: 0
    val matches = chunks.indices.filter { nameMatchRanges(chunks[it].text, query).isNotEmpty() }
    return DocUi.Text(chunks.map { it.text }, match, query, matches)
}

/** The match after ([step] 1) or before (-1) paragraph [from], wrapping round the ends; null when nothing matches. */
internal fun stepMatch(matches: List<Int>, from: Int, step: Int): Int? =
    if (step > 0) matches.firstOrNull { it > from } ?: matches.firstOrNull() else matches.lastOrNull { it < from } ?: matches.lastOrNull()
