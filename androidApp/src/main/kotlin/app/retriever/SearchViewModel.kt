package app.retriever

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.retriever.core.FileState
import app.retriever.core.IndexState
import app.retriever.core.Kind
import app.retriever.core.ReadExtent
import app.retriever.core.Retriever
import app.retriever.core.SearchEngine
import app.retriever.core.SourceFile
import app.retriever.core.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

// Not a data class: equality is identity, so one photo search is one search key.
class Similar(val label: String, val jpeg: ByteArray, val thumb: ImageBitmap? = null)

data class SearchUi(
    val query: String = "",
    val chip: Kind? = null,
    val similar: Similar? = null,
    /** null: nothing searched. */
    val hits: List<SearchEngine.Hit>? = null,
    /** Counts the searches that set [hits], so the screen can start each new answer at its top. */
    val answer: Int = 0,
    /** The trimmed query [hits] answer; a search by photo answers "". */
    val hitsFor: String = "",
    /** hits[0 until strongCount] are strong matches, the rest less likely. The default makes every hit strong. */
    val strongCount: Int = Int.MAX_VALUE,
    /** Files in [hits] that were not read whole. */
    val extents: Map<Long, ReadExtent> = emptyMap(),
    /** This phone finds files by their names and words only. */
    val textOnly: Boolean = false,
    val searching: Boolean = false,
    val failed: Boolean = false,
    val vectorLane: Boolean = true,
    val setUp: Boolean = false,
    val recent: List<String> = emptyList(),
    /** null until the store has answered. */
    val counts: Map<Kind, Long>? = null,
    val searchable: Long = 0L,
    val waiting: Long = 0L,
    val recentFiles: List<SourceFile> = emptyList(),
)

/** [jpegOf] turns a content uri into a JPEG at most 1024 px on its longest side, or null. */
@OptIn(FlowPreview::class)
class SearchViewModel(
    private val retriever: Retriever,
    private val jpegOf: (String) -> ByteArray?,
) : ViewModel() {
    private val _ui = MutableStateFlow(SearchUi(textOnly = retriever.textOnly))
    val ui: StateFlow<SearchUi> = _ui.asStateFlow()
    val indexState: StateFlow<IndexState> = retriever.state
    private val retries = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            combine(
                _ui.map { it.query.trim() }.distinctUntilChanged().debounce(250),
                _ui.map { it.chip }.distinctUntilChanged(),
                _ui.map { it.similar }.distinctUntilChanged(),
                retries,
                // A search by photo ignores the words, so the query settling to "" must not start it again.
            ) { q, chip, similar, retry -> Key(if (similar != null) "" else q, chip, similar, retry) }
                .distinctUntilChanged()
                .collectLatest { search(it.q, it.chip, it.similar) }
        }
        viewModelScope.launch { retriever.setUp.collect { s -> _ui.update { it.copy(setUp = s == true) } } }
        viewModelScope.launch {
            retriever.counts().collect { c ->
                _ui.update {
                    it.copy(
                        counts = c.byKind,
                        searchable = c.byState[FileState.INDEXED] ?: 0L,
                        waiting = c.byState[FileState.PENDING] ?: 0L,
                    )
                }
            }
        }
        // At most one reload every 2 s while files are being counted, and always one after the last change.
        viewModelScope.launch {
            retriever.counts().conflate().collect {
                quietly { _ui.update { it.copy(recentFiles = retriever.files(null, null, Store.Sort.NEWEST, 12, 0).map { f -> f.file }) } }
                delay(2_000)
            }
        }
        viewModelScope.launch { loadRecent() }
    }

    private data class Key(val q: String, val chip: Kind?, val similar: Similar?, val retry: Int)

    private suspend fun search(q: String, chip: Kind?, similar: Similar?) {
        if (similar == null && q.isEmpty()) {
            _ui.update { it.copy(hits = null, hitsFor = "", searching = false, failed = false) }
            return
        }
        _ui.update { it.copy(searching = true, failed = false) }
        val kinds = chip?.let(::setOf)
        try {
            val r = if (similar != null) retriever.searchImage(similar.jpeg, kinds, limit = 60) else retriever.search(q, kinds, limit = 60)
            // Fetched before the update, in this job: a cancelled search leaves the old hits and their extents alone.
            val extents = retriever.readExtents(r.hits.map { it.fileId })
            // A search by photo has no weak tail.
            val strong = if (similar != null) r.hits.size else r.strongCount
            _ui.update { it.copy(hits = r.hits, answer = it.answer + 1, hitsFor = q,strongCount = strong, extents = extents, vectorLane = r.vectorLane, searching = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Retriever", "search failed", e)
            _ui.update { it.copy(searching = false, failed = true) }
        }
    }

    /** Typing words ends a search by photo; clearing the words drops the kind filter with them. */
    fun setQuery(q: String) = _ui.update {
        if (q.isBlank()) it.copy(query = q, chip = if (it.query.isNotBlank()) null else it.chip) else it.copy(query = q, similar = null)
    }

    fun setChip(kind: Kind?) = _ui.update { it.copy(chip = kind) }

    fun retry() = retries.update { it + 1 }

    /** The keyboard's search key, or a suggestion picked. */
    fun submit(q: String = _ui.value.query) {
        setQuery(q)
        record(q)
    }

    fun opened(hit: SearchEngine.Hit) {
        if (_ui.value.similar == null) record(_ui.value.query)
    }

    private fun record(q: String) {
        if (q.isBlank()) return
        viewModelScope.launch {
            quietly { retriever.recordQuery(q) }
            loadRecent()
        }
    }

    fun removeRecent(q: String) = viewModelScope.launch {
        quietly { retriever.removeRecentQuery(q) }
        loadRecent()
    }

    fun clearRecent() = viewModelScope.launch {
        quietly { retriever.clearRecentQueries() }
        loadRecent()
    }

    private suspend fun loadRecent() = quietly { _ui.update { it.copy(recent = retriever.recentQueries()) } }

    fun findSimilar(file: SourceFile) = showSimilar(file.uri, file.name)

    /** A photo from the picker. */
    fun searchPhoto(uri: String) = showSimilar(uri, "your photo")

    private fun showSimilar(uri: String, label: String) = viewModelScope.launch {
        val jpeg = withContext(Dispatchers.IO) { runCatching { jpegOf(uri) }.getOrNull() } ?: return@launch
        // Decoded once here, not in composition: the chip shows it as its avatar.
        val thumb = withContext(Dispatchers.Default) { runCatching { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap() }.getOrNull() }
        _ui.update { it.copy(similar = Similar(label, jpeg, thumb), query = "") }
    }

    fun clearSimilar() = _ui.update { it.copy(similar = null) }

    fun setUiBusy(busy: Boolean) = retriever.setUiBusy(busy)

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Retriever", "search screen", e)
        }
    }
}
