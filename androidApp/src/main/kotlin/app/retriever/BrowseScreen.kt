package app.retriever

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.retriever.core.Kind
import app.retriever.core.Retriever
import app.retriever.core.SourceFile
import app.retriever.core.Store
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val PAGE = 200

/** Start the next page when the last visible item is this close to the end. */
private const val LOAD_AHEAD = 20

private val GRID_KINDS = setOf(Kind.PHOTO, Kind.SCREENSHOT, Kind.VIDEO)

class BrowseViewModel(private val retriever: Retriever) : ViewModel() {
    private val _files = MutableStateFlow<List<Store.StoredFile>>(emptyList())
    val files: StateFlow<List<Store.StoredFile>> = _files.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private var kind: Kind? = null
    private var folder: String? = null
    private var sort = Store.Sort.NEWEST
    private var starredOnly = false
    private var generation = 0
    private var busy = false
    private var exhausted = false

    fun load(kind: String?, folder: String?, sort: String, starredOnly: Boolean = false) {
        val k = Kind.entries.firstOrNull { it.name == kind }
        val s = Store.Sort.entries.firstOrNull { it.name == sort } ?: Store.Sort.NEWEST
        // A rotation re-runs the screen's LaunchedEffect; keep the pages already loaded.
        if (generation > 0 && k == this.kind && folder == this.folder && s == this.sort && starredOnly == this.starredOnly) return
        this.kind = k
        this.folder = folder
        this.sort = s
        this.starredOnly = starredOnly
        generation++
        _loading.value = true
        busy = false
        exhausted = false
        _files.value = emptyList()
        loadMore()
    }

    fun loadMore() {
        if (busy || exhausted) return
        busy = true
        val gen = generation
        val offset = _files.value.size
        viewModelScope.launch {
            val page = if (starredOnly) retriever.starredFiles(PAGE, offset) else retriever.files(kind, folder, sort, PAGE, offset)
            if (gen != generation) return@launch
            // ponytail: OFFSET paging over a table the indexer changes; rows can shift between pages.
            // Dedupe so a shifted row never repeats a lazy-list key; keyset paging if skips matter.
            _files.update { (it + page).distinctBy { f -> f.id } }
            _loading.value = false
            exhausted = page.size < PAGE
            busy = false
        }
    }
}

/** The Large files entry is every file by size; sorted another way it is just all files. Starred keeps its name. */
internal fun browseTitle(route: BrowseRoute, sort: String): String =
    if (route.kind == null && route.folder == null && !route.starredOnly && sort != "LARGEST") "All files" else route.title

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(route: BrowseRoute, onNavigate: (Any) -> Unit, onBack: () -> Unit) {
    val vm = appViewModel(key = "browse:$route") { BrowseViewModel(it.retriever) }
    val loaded by vm.files.collectAsState()
    val star = appViewModel(key = "star") { StarViewModel(it.retriever) }
    val starred by star.starred.collectAsState()
    // A file unstarred here leaves the Starred list at once, without reloading its pages.
    val files = if (route.starredOnly) loaded.filter { it.file.uri in starred } else loaded
    val loading by vm.loading.collectAsState()
    var sort by rememberSaveable { mutableStateOf(route.sort) }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // As on a search result: starring says so, with Undo, which also brings an unstarred file back to this list.
    val onStar = remember(star, snackbar, scope) { starWithUndo({ file, on -> star.set(file.uri, on) }, snackbar, scope) }

    LaunchedEffect(route, sort) { vm.load(route.kind, route.folder, sort, route.starredOnly) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(browseTitle(route, sort)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        BrowseContent(
            files = files,
            sort = sort,
            loading = loading,
            kind = Kind.entries.firstOrNull { it.name == route.kind },
            starredOnly = route.starredOnly,
            padding = padding,
            onSort = { sort = it },
            onLoadMore = vm::loadMore,
            onOpen = { Opener.open(context, it, navigate = onNavigate) },
            starred = starred,
            onStar = onStar,
        )
    }
}

/** Photos, screenshots and videos as a grid of thumbnails; everything else as rows. */
@Composable
fun BrowseContent(
    files: List<Store.StoredFile>,
    sort: String,
    loading: Boolean,
    kind: Kind?,
    padding: PaddingValues,
    onSort: (String) -> Unit,
    onLoadMore: () -> Unit,
    onOpen: (SourceFile) -> Unit,
    starredOnly: Boolean = false,
    starred: Set<String> = emptySet(),
    onStar: (SourceFile, Boolean) -> Unit = { _, _ -> },
) {
    Column(Modifier.padding(top = padding.calculateTopPadding())) {
        // Starred is ordered by when you starred, so the sort chips would not apply.
        if (!starredOnly) Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Store.Sort.entries.forEach { option ->
                FilterChip(
                    selected = sort == option.name,
                    onClick = { onSort(option.name) },
                    label = { Text(option.label()) },
                )
            }
        }

        if (files.isEmpty() && loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        } else if (files.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (starredOnly) {
                    EmptyState(
                        title = "Nothing starred yet",
                        description = "Touch and hold a file, or tap the star while viewing it, to keep it here.",
                        icon = Icons.Outlined.StarBorder,
                        art = R.drawable.illus_empty,
                    )
                } else {
                    EmptyState(
                        title = "Nothing here yet",
                        description = "Files show up here once Retriever finds them on your phone.",
                        icon = Icons.Outlined.FolderOpen,
                        art = R.drawable.illus_empty,
                    )
                }
            }
        } else {
            val listPadding = contentPaddingFor(PaddingValues(bottom = padding.calculateBottomPadding()))
            if (kind in GRID_KINDS) {
                val state = rememberLazyGridState()
                LoadMoreEffect(files.size, { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index }, onLoadMore)
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    state = state,
                    contentPadding = listPadding,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                ) {
                    items(files, key = { it.id }) { stored ->
                        val on = stored.file.uri in starred
                        StarMenu(on, { onStar(stored.file, !on) }) { menu ->
                            ThumbCell(file = stored.file, onClick = { onOpen(stored.file) }, onLongClick = menu, onLongClickLabel = "More options")
                        }
                    }
                }
            } else {
                val state = rememberLazyListState()
                LoadMoreEffect(files.size, { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index }, onLoadMore)
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = state,
                        contentPadding = contentPaddingFor(PaddingValues(bottom = padding.calculateBottomPadding()), side = gutter()),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(files, key = { it.id }) { stored ->
                            val on = stored.file.uri in starred
                            StarMenu(on, { onStar(stored.file, !on) }) { menu ->
                                FileRow(file = stored.file, onClick = { onOpen(stored.file) }, onLongClick = menu, onLongClickLabel = "More options")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadMoreEffect(count: Int, lastVisible: () -> Int?, onLoadMore: () -> Unit) {
    LaunchedEffect(count) {
        snapshotFlow(lastVisible).distinctUntilChanged().collect { last ->
            if (last != null && last >= count - LOAD_AHEAD) onLoadMore()
        }
    }
}

class StarViewModel(private val retriever: Retriever) : ViewModel() {
    val starred: StateFlow<Set<String>> = retriever.starred
    fun toggle(uri: String) = set(uri, uri !in starred.value)

    fun set(uri: String, on: Boolean) {
        viewModelScope.launch { retriever.setStarred(uri, on) }
    }
}

/** A file list's long-press menu: Star or Remove star, as on a search result, so a photo can be unstarred from a list. */
@Composable
private fun StarMenu(on: Boolean, toggle: () -> Unit, content: @Composable (onLongClick: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        content { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(if (on) "Remove star" else "Star") },
                leadingIcon = { Icon(if (on) Icons.Outlined.StarBorder else Icons.Filled.Star, contentDescription = null) },
                onClick = {
                    open = false
                    toggle()
                },
            )
        }
    }
}

/** The viewers' top-bar star: reflects and toggles whether [uri] is starred. */
@Composable
fun StarButton(uri: String) {
    val vm = appViewModel(key = "star") { StarViewModel(it.retriever) }
    val starred by vm.starred.collectAsState()
    val on = uri in starred
    IconButton(onClick = { vm.toggle(uri) }) {
        Icon(
            if (on) Icons.Filled.Star else Icons.Outlined.StarBorder,
            contentDescription = if (on) "Remove star" else "Star this file",
        )
    }
}

private fun Store.Sort.label(): String = when (this) {
    Store.Sort.NEWEST -> "Newest"
    Store.Sort.LARGEST -> "Largest"
    Store.Sort.NAME -> "Name"
}
