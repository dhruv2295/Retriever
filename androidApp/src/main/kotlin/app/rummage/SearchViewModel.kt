package app.rummage

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.rummage.core.Kind
import app.rummage.core.SearchEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

data class SearchUi(
    val query: String = "",
    /** null while the query is empty (home) or the first search is running. */
    val hits: List<SearchEngine.Hit>? = null,
    val chip: Kind? = null,
    val note: String? = null,
    val notReady: Boolean = false,
    val failed: Boolean = false,
    val counts: Map<Kind, Long> = emptyMap(),
    val searchable: Long = 0,
    val waiting: Long = 0,
    val skipped: Long = 0,
    val searching: Boolean = false,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as RummageApp).graph
    private val _ui = MutableStateFlow(SearchUi())
    val ui: StateFlow<SearchUi> = _ui.asStateFlow()
    val indexState: StateFlow<IndexState> = graph.indexState

    init {
        viewModelScope.launch {
            _ui.map { it.query }.distinctUntilChanged().debounce(250).collectLatest { search(it.trim()) }
        }
        // indexState changes once per indexed file; a StateFlow collector skips what it missed
        // during the delay, so the counts refresh at most every 2 s.
        viewModelScope.launch {
            graph.indexState.collect {
                loadCounts()
                delay(2000)
            }
        }
    }

    fun setQuery(q: String) = _ui.update {
        if (q.isBlank()) it.copy(query = q, hits = null, chip = null, note = null, notReady = false, failed = false, searching = false)
        else it.copy(query = q)
    }

    fun setChip(kind: Kind?) = _ui.update { it.copy(chip = kind) }

    private suspend fun loadCounts() {
        val (kinds, states) = withContext(Dispatchers.IO) { graph.store.countsByKind() to graph.store.countsByState() }
        _ui.update {
            it.copy(
                counts = kinds,
                searchable = states["indexed"] ?: 0,
                waiting = states["pending"] ?: 0,
                skipped = states.filterKeys { k -> k != "indexed" && k != "pending" }.values.sum(),
            )
        }
    }

    private suspend fun search(q: String) {
        if (q.isEmpty()) return
        _ui.update { it.copy(searching = true) }
        val engine = try {
            // Reads the profile and may open the model: never on the main thread.
            withContext(Dispatchers.IO) { graph.searchEngine() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Rummage", "search engine unavailable", e)
            _ui.update { it.copy(hits = emptyList(), notReady = true, failed = false, note = null, searching = false) }
            return
        }
        var note: String? = null
        try {
            val hits = try {
                withContext(Dispatchers.Default) { engine.search(q, SearchEngine.Mode.MERGED, 50, null) }
            } catch (e: IllegalStateException) {
                Log.w("Rummage", "falling back to keyword search", e)
                note = "Searching by words only for now."
                withContext(Dispatchers.Default) { engine.search(q, SearchEngine.Mode.KEYWORD, 50, null) }
            }
            val waiting = withContext(Dispatchers.IO) { graph.store.countsByState()["pending"] ?: 0 }
            _ui.update {
                it.copy(hits = hits, chip = null, note = note, notReady = false, failed = false, waiting = waiting, searching = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Rummage", "search failed", e)
            _ui.update { it.copy(hits = emptyList(), notReady = false, failed = true, note = null, searching = false) }
        }
    }
}
