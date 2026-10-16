package app.retriever

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.retriever.core.FileState
import app.retriever.core.IndexState
import app.retriever.core.Kind
import app.retriever.core.Retriever
import app.retriever.core.SearchEngine
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

data class SearchUi(
    val query: String = "",
    /** null while the query is empty (home) or the first search is running. */
    val hits: List<SearchEngine.Hit>? = null,
    val chip: Kind? = null,
    /** The chip's own search within its kind; null until it arrives (the screen filters [hits] meanwhile). */
    val chipHits: List<SearchEngine.Hit>? = null,
    val note: String? = null,
    val notReady: Boolean = false,
    val failed: Boolean = false,
    val counts: Map<Kind, Long> = emptyMap(),
    val searchable: Long = 0,
    val waiting: Long = 0,
    val skipped: Long = 0,
    val searching: Boolean = false,
)

@OptIn(FlowPreview::class)
class SearchViewModel(private val retriever: Retriever) : ViewModel() {
    private val _ui = MutableStateFlow(SearchUi())
    val ui: StateFlow<SearchUi> = _ui.asStateFlow()
    val indexState: StateFlow<IndexState> = retriever.state

    init {
        viewModelScope.launch {
            _ui.map { it.query }.distinctUntilChanged().debounce(250).collectLatest { search(it.trim()) }
        }
        viewModelScope.launch {
            retriever.counts().collect { c ->
                _ui.update {
                    it.copy(
                        counts = c.byKind,
                        searchable = c.byState[FileState.INDEXED] ?: 0,
                        waiting = c.byState[FileState.PENDING] ?: 0,
                        skipped = c.byState[FileState.SKIPPED] ?: 0,
                    )
                }
            }
        }
    }

    fun setQuery(q: String) = _ui.update {
        if (q.isBlank()) it.copy(query = q, hits = null, chip = null, note = null, notReady = false, failed = false, searching = false)
        else it.copy(query = q)
    }

    private var chipJob: Job? = null

    /** A chip searches again inside its kind, so it isn't limited to the few of that kind the mixed top 20 held. */
    fun setChip(kind: Kind?) {
        _ui.update { it.copy(chip = kind, chipHits = null) }
        chipJob?.cancel()
        val q = _ui.value.query.trim()
        if (kind == null || q.isEmpty() || retriever.setUp.value != true) return
        chipJob = viewModelScope.launch {
            val hits = try {
                retriever.search(q, setOf(kind)).hits
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Retriever", "search failed", e)
                return@launch
            }
            _ui.update { if (it.chip == kind && it.query.trim() == q) it.copy(chipHits = hits) else it }
        }
    }

    private suspend fun search(q: String) {
        if (q.isEmpty()) return
        if (retriever.setUp.value != true) {
            _ui.update { it.copy(hits = emptyList(), notReady = true, failed = false, note = null, searching = false) }
            return
        }
        _ui.update { it.copy(searching = true) }
        try {
            val result = retriever.search(q)
            val note = if (result.vectorLane) null else "Searching by words only for now."
            chipJob?.cancel()
            _ui.update { it.copy(hits = result.hits, chip = null, chipHits = null, note = note, notReady = false, failed = false, searching = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Retriever", "search failed", e)
            _ui.update { it.copy(hits = emptyList(), notReady = false, failed = true, note = null, searching = false) }
        }
    }
}
