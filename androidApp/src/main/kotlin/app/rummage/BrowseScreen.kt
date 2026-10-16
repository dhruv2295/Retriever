package app.rummage

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.rummage.core.Kind
import app.rummage.core.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PAGE = 200

/** Start the next page when the last visible row is this close to the end. */
private const val LOAD_AHEAD = 20

class BrowseViewModel(application: Application) : AndroidViewModel(application) {
    private val store = getApplication<RummageApp>().graph.store

    private val _files = MutableStateFlow<List<Store.StoredFile>>(emptyList())
    val files: StateFlow<List<Store.StoredFile>> = _files.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private var kind: Kind? = null
    private var folder: String? = null
    private var sort = Store.Sort.NEWEST
    private var generation = 0
    private var busy = false
    private var exhausted = false

    fun load(kind: String?, folder: String?, sort: String) {
        val k = Kind.entries.firstOrNull { it.name == kind }
        val s = Store.Sort.entries.firstOrNull { it.name == sort } ?: Store.Sort.NEWEST
        // A rotation re-runs the screen's LaunchedEffect; keep the pages already loaded.
        if (generation > 0 && k == this.kind && folder == this.folder && s == this.sort) return
        this.kind = k
        this.folder = folder
        this.sort = s
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
            val page = withContext(Dispatchers.IO) {
                store.files(kind, folder, sort, PAGE, offset)
            }
            if (gen != generation) return@launch
            // ponytail: OFFSET paging over a table the indexer changes; rows can shift between pages.
            // Dedupe so a shifted row never repeats a LazyColumn key; keyset paging if skips matter.
            _files.update { (it + page).distinctBy { f -> f.id } }
            _loading.value = false
            exhausted = page.size < PAGE
            busy = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(route: BrowseRoute, onNavigate: (Any) -> Unit, onBack: () -> Unit) {
    val vm = viewModel<BrowseViewModel>(key = "browse:$route")
    val files by vm.files.collectAsState()
    val loading by vm.loading.collectAsState()
    var sort by rememberSaveable { mutableStateOf(route.sort) }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    LaunchedEffect(route, sort) { vm.load(route.kind, route.folder, sort) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to files.size }
            .distinctUntilChanged()
            .collect { (last, size) ->
                if (last != null && last >= size - LOAD_AHEAD) vm.loadMore()
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(route.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Store.Sort.entries.forEach { option ->
                    FilterChip(
                        selected = sort == option.name,
                        onClick = { sort = option.name },
                        label = { Text(option.label()) },
                    )
                }
            }

            if (files.isEmpty() && loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            } else if (files.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        title = "Nothing here yet",
                        description = "Files show up here once Rummage has found them on your phone.",
                        icon = Icons.Outlined.FolderOpen,
                    )
                }
            } else {
                LazyColumn(state = listState) {
                    items(files, key = { it.id }) { stored ->
                        FileRow(
                            file = stored.file,
                            onClick = { Opener.open(context, stored.file, toPlayer = onNavigate) },
                        )
                    }
                }
            }
        }
    }
}

private fun Store.Sort.label(): String = when (this) {
    Store.Sort.NEWEST -> "Newest"
    Store.Sort.LARGEST -> "Largest"
    Store.Sort.NAME -> "Name"
}
