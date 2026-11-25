package app.retriever.browse

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.retriever.core.FolderNode
import app.retriever.core.Kind
import app.retriever.core.Person
import app.retriever.core.Retriever
import app.retriever.core.Store
import app.retriever.core.Words
import app.retriever.files.Selection
import app.retriever.nav.BrowseRoute
import app.retriever.starred.Starring
import app.retriever.ui.launchCatching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val PAGE = 200

/** Everything Browse draws. [loading] is the first page only; [error] is the last page that failed, kept until a retry. */
data class BrowseUi(
    val files: List<Store.StoredFile> = emptyList(),
    val sort: String = "NEWEST",
    val loading: Boolean = true,
    val error: String? = null,
    val exhausted: Boolean = false,
    /** Retriever.readInside at the last reload; a kind outside it reads as Name only. */
    val inside: Set<Kind> = Kind.entries.toSet() - Kind.OTHER,
)

/** The sort is kept in [saved], so it survives rotation and process death; the first page loads at once. */
class BrowseViewModel(private val retriever: Retriever, private val saved: SavedStateHandle, val route: BrowseRoute) : ViewModel() {
    val stars = Starring(retriever, viewModelScope)
    val selection = Selection(saved)

    /** A folder's own subfolders, read again when files change; null until read, and empty off a folder. */
    val subfolders: StateFlow<List<FolderNode>?> = retriever.fileRevision
        .map { route.folder?.let { folder -> retriever.subfolders(folder) }.orEmpty() }
        .catch {
            Log.e("Retriever", "BrowseViewModel subfolders", it)
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val paged = MutableStateFlow(BrowseUi(sort = saved[SORT] ?: route.sort))

    /** A file unstarred here leaves the Starred list at once, without reloading its pages. */
    val uiState: StateFlow<BrowseUi> = combine(paged, stars.starred) { ui, starred ->
        if (route.starredOnly) ui.copy(files = ui.files.filter { it.file.uri in starred }) else ui
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), paged.value)

    private val kind = Kind.entries.firstOrNull { it.name == route.kind }
    private var generation = 0
    private var busy = false

    /** The next fetch re-reads every loaded row from offset 0 and swaps them in, so a retry after a failed refresh redoes it. */
    private var replacing = false

    init {
        viewModelScope.launch { retriever.fileRevision.collect { if (it > 0) reload(keep = true) } }
        reload()
    }

    fun setSort(value: String) {
        if (value == paged.value.sort) return
        saved[SORT] = value
        reload()
    }

    /**
     * A sort change starts again from the top. A rename, delete or merge ([keep]) leaves the rows on screen until their
     * fresh copy lands, so the list keeps its scroll position and the viewer's morph its cell.
     */
    private fun reload(keep: Boolean = false) {
        generation++
        busy = false
        replacing = keep
        if (keep) {
            paged.update { it.copy(error = null, exhausted = false, inside = retriever.readInside()) }
        } else {
            paged.update { BrowseUi(sort = saved[SORT] ?: route.sort, inside = retriever.readInside()) }
        }
        fetch()
    }

    /** The list scrolled near its end. A failed page waits for [retry] rather than failing again on every scroll. */
    fun loadMore() {
        if (paged.value.error == null) fetch()
    }

    fun retry() = fetch()

    /** Everyone grouped, for a person's title and "Same person as…"; empty off a person's screen. */
    val people: StateFlow<List<Person>> = (if (route.person == null) flowOf(emptyList()) else retriever.people())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _failure = MutableStateFlow<String?>(null)

    /** A person's rename, merge or "Not this person", or a folder's exclusion, failed: the screen says so once, then calls [failureShown]. */
    val failure: StateFlow<String?> = _failure

    fun failureShown() {
        _failure.value = null
    }

    private fun personFailed(e: Exception) {
        Log.e("Retriever", "BrowseViewModel person", e)
        _failure.value = Words.strings.couldntChangePerson
    }

    /** Core drops the folder's files at once, and the lists reload with them gone. */
    fun exclude(folder: String) {
        launchCatching({
            Log.e("Retriever", "BrowseViewModel exclude", it)
            _failure.value = Words.strings.couldntStopSearchingFolder
        }) { retriever.updatePrefs { it.copy(excludedFolders = it.excludedFolders + folder) } }
    }

    fun rename(name: String) {
        val p = route.person ?: return
        launchCatching(::personFailed) { retriever.renamePerson(p, name) }
    }

    /** [other] is this person too: their photos join these, and their name when this person has none. */
    fun sameAs(other: Long) {
        val p = route.person ?: return
        launchCatching(::personFailed) {
            retriever.mergePeople(into = p, from = other)
            reload(keep = true)
        }
    }

    /** The photo leaves this person at once, without reloading the pages. */
    fun notThisPerson(file: Store.StoredFile) {
        val p = route.person ?: return
        launchCatching(::personFailed) {
            retriever.notThisPerson(p, file.id)
            paged.update { it.copy(files = it.files - file) }
        }
    }

    fun setScrolling(scrolling: Boolean) = retriever.setUiBusy(scrolling)

    override fun onCleared() = retriever.setUiBusy(false)

    private fun fetch() {
        val ui = paged.value
        if (busy || ui.exhausted) return
        busy = true
        val gen = generation
        val replace = replacing
        val offset = if (replace) 0 else ui.files.size
        val limit = if (replace) maxOf(PAGE, ui.files.size) else PAGE
        val order = Store.Sort.entries.firstOrNull { it.name == ui.sort } ?: Store.Sort.NEWEST
        paged.update { it.copy(error = null, loading = it.files.isEmpty()) }
        launchCatching(onError = {
            Log.e("Retriever", "BrowseViewModel", it)
            if (gen == generation) {
                busy = false
                paged.update { s -> s.copy(loading = false, error = Words.strings.couldntLoadTheseFiles) }
            }
        }) {
            val from = route.takenFrom
            val to = route.takenTo
            val person = route.person
            val page = when {
                person != null -> retriever.personPhotos(person, limit, offset)
                route.starredOnly -> retriever.starredFiles(limit, offset)
                from != null && to != null -> retriever.filesTakenBetween(from, to, limit, offset)
                else -> retriever.files(kind, route.folder, order, limit, offset)
            }
            if (gen != generation) return@launchCatching
            // ponytail: OFFSET paging over a table the indexer changes; rows can shift between pages.
            // Dedupe so a shifted row never repeats a lazy-list key; keyset paging if skips matter.
            paged.update { it.copy(files = (if (replace) page else it.files + page).distinctBy { f -> f.id }, loading = false, exhausted = page.size < limit) }
            // The list was read again from its top: a picked file not among its rows is gone, or no longer loaded.
            if (offset == 0) selection.keepOnly(page.map { it.file.uri })
            replacing = false
            busy = false
        }
    }

    private companion object {
        const val SORT = "browse.sort" // not "sort": that key already holds the route's argument
    }
}
