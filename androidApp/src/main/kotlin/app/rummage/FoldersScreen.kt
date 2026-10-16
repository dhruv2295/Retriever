package app.rummage

import android.app.Application
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.rummage.core.excludes
import app.rummage.core.prefs
import app.rummage.core.setPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FoldersViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = getApplication<RummageApp>().graph
    private val store = graph.store

    private val _folders = MutableStateFlow<List<Pair<String, Long>>>(emptyList())
    val folders: StateFlow<List<Pair<String, Long>>> = _folders.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch {
            _folders.value = withContext(Dispatchers.IO) { store.folders() }
            _loading.value = false
        }
    }

    fun exclude(folder: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val prefs = store.prefs()
                store.setPrefs(prefs.copy(excludedFolders = prefs.excludedFolders + folder))
                val newPrefs = store.prefs()
                val uris = store.folders().map { it.first }
                    .filter { newPrefs.excludes(it) }
                    .flatMap { f -> store.files(folder = f, limit = Int.MAX_VALUE).map { it.file.uri } }
                store.removeFiles(uris)
                // The vector table still holds the removed chunks until the search engine reloads.
                graph.notifyIndexChanged()
                IndexScheduler.indexNow(getApplication<Application>())
            }
            refresh()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(onNavigate: (Any) -> Unit, onBack: () -> Unit) {
    val vm = viewModel<FoldersViewModel>()
    val folders by vm.folders.collectAsState()
    val loading by vm.loading.collectAsState()
    var confirming by remember { mutableStateOf<Pair<String, Long>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Folders") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (folders.isEmpty() && loading) {
            LinearProgressIndicator(Modifier.padding(padding).fillMaxWidth().padding(horizontal = 16.dp))
        } else if (folders.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = "No folders yet",
                    description = "Folders appear once Rummage has looked through your phone.",
                    icon = Icons.Outlined.FolderOpen,
                )
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(folders, key = { it.first }) { (path, count) ->
                    FolderRow(
                        path = path,
                        count = count,
                        onOpen = {
                            onNavigate(BrowseRoute(folder = path, title = path.substringAfterLast('/').ifEmpty { "Top level" }))
                        },
                        onExclude = { confirming = path to count },
                    )
                }
            }
        }
    }

    // Excluding drops everything Rummage already read there; bringing it back means reading it all again.
    confirming?.let { (path, count) ->
        val name = path.substringAfterLast('/').ifEmpty { "Top level" }
        ConfirmDialog(
            title = "Stop searching $name?",
            message = "Rummage will forget the ${countLabel(count)} in $name. You can bring the folder back in Status.",
            confirmLabel = "Stop searching",
            onConfirm = {
                confirming = null
                vm.exclude(path)
            },
            onDismiss = { confirming = null },
        )
    }
}

@Composable
private fun FolderRow(path: String, count: Long, onOpen: () -> Unit, onExclude: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }

    Box {
        ListItem(
            headlineContent = {
                Text(path.ifEmpty { "Top level" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = { Text(countLabel(count)) },
            leadingContent = { Icon(Icons.Outlined.Folder, contentDescription = null) },
            modifier = Modifier.combinedClickable(onClick = onOpen, onLongClick = { showMenu = true }),
        )
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text("Don't search this folder") },
                onClick = {
                    showMenu = false
                    onExclude()
                },
            )
        }
    }
}

private fun countLabel(count: Long): String =
    if (count == 1L) "1 file" else "%,d files".format(count)
