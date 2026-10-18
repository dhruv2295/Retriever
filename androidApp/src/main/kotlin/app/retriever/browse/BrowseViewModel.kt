package app.retriever.browse

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.retriever.core.Kind
import app.retriever.core.Retriever
import app.retriever.core.Store
import app.retriever.nav.BrowseRoute
import app.retriever.starred.Starring
import app.retriever.ui.launchCatching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

private const val PAGE = 200

/** Everything Browse draws. [loading] is the first page only; [error] is the last page that failed, kept until a retry. */
data class BrowseUi(
    val files: List<Store.StoredFile> = emptyList(),
    val sort: String = "NEWEST",
    val loading: Boolean = true,
    val error: String? = null,
    val exhausted: Boolean = false,
)

/** Arguments and sort come from [saved], so they survive rotation and process death; the first page loads at once. */
class BrowseViewModel(private val retriever: Retriever, private val saved: SavedStateHandle) : ViewModel() {
    val route = saved.toRoute<BrowseRoute>()
    val stars = Starring(retriever, viewModelScope)

    private val paged = MutableStateFlow(BrowseUi(sort = saved[SORT] ?: route.sort))

    /** A file unstarred here leaves the Starred list at once, without reloading its pages. */
    val uiState: StateFlow<BrowseUi> = combine(paged, stars.starred) { ui, starred ->
        if (route.starredOnly) ui.copy(files = ui.files.filter { it.file.uri in starred }) else ui
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), paged.value)

    private val kind = Kind.entries.firstOrNull { it.name == route.kind }
    private var generation = 0
    private var busy = false

    init {
        reload()
    }

    fun setSort(value: String) {
        if (value == paged.value.sort) return
        saved[SORT] = value
        reload()
    }

    private fun reload() {
        generation++
        busy = false
        paged.update { BrowseUi(sort = saved[SORT] ?: route.sort) }
        fetch()
    }

    /** The list scrolled near its end. A failed page waits for [retry] rather than failing again on every scroll. */
    fun loadMore() {
        if (paged.value.error == null) fetch()
    }

    fun retry() = fetch()

    fun setScrolling(scrolling: Boolean) = retriever.setUiBusy(scrolling)

    override fun onCleared() = retriever.setUiBusy(false)

    private fun fetch() {
        val ui = paged.value
        if (busy || ui.exhausted) return
        busy = true
        val gen = generation
        val order = Store.Sort.entries.firstOrNull { it.name == ui.sort } ?: Store.Sort.NEWEST
        paged.update { it.copy(error = null, loading = it.files.isEmpty()) }
        launchCatching(onError = {
            Log.e("Retriever", "BrowseViewModel", it)
            if (gen == generation) {
                busy = false
                paged.update { s -> s.copy(loading = false, error = "Couldn't load these files.") }
            }
        }) {
            val offset = ui.files.size
            val from = route.takenFrom
            val to = route.takenTo
            val page = when {
                route.starredOnly -> retriever.starredFiles(PAGE, offset)
                from != null && to != null -> retriever.filesTakenBetween(from, to, PAGE, offset)
                else -> retriever.files(kind, route.folder, order, PAGE, offset)
            }
            if (gen != generation) return@launchCatching
            // ponytail: OFFSET paging over a table the indexer changes; rows can shift between pages.
            // Dedupe so a shifted row never repeats a lazy-list key; keyset paging if skips matter.
            paged.update { it.copy(files = (it.files + page).distinctBy { f -> f.id }, loading = false, exhausted = page.size < PAGE) }
            busy = false
        }
    }

    private companion object {
        const val SORT = "browse.sort" // not "sort": that key already holds the route's argument
    }
}
