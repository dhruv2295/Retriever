package app.retriever

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.retriever.core.excludes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FoldersViewModel(private val graph: AppGraph) : ViewModel() {
    private val retriever = graph.retriever

    private val _folders = MutableStateFlow<List<Pair<String, Long>>>(emptyList())
    val folders: StateFlow<List<Pair<String, Long>>> = _folders.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch {
            // Excluded folders stay in the index until the next sync drops them; hide them now.
            _folders.value = retriever.folders().filterNot { retriever.prefs.value.excludes(it.first) }
            _loading.value = false
        }
    }

    /** The changed exclude list makes the next sync a full scan, which forgets the folder's files. */
    fun exclude(folder: String) {
        viewModelScope.launch {
            retriever.updatePrefs { it.copy(excludedFolders = it.excludedFolders + folder) }
            IndexScheduler.indexNow(graph.app)
            refresh()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(onNavigate: (Any) -> Unit, onBack: () -> Unit) {
    val vm = appViewModel { FoldersViewModel(it) }
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
                    description = "Folders appear once Retriever has looked through your phone.",
                    icon = Icons.Outlined.FolderOpen,
                    art = R.drawable.illus_empty,
                )
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                LazyColumn(contentPadding = contentPaddingFor(padding, side = gutter()), modifier = Modifier.fillMaxSize()) {
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
    }

    // Excluding drops everything Retriever already read there; bringing it back means reading it all again.
    confirming?.let { (path, count) ->
        val name = path.substringAfterLast('/').ifEmpty { "Top level" }
        ConfirmDialog(
            title = "Stop searching $name?",
            message = "Retriever will forget the ${countLabel(count)} in $name. You can bring it back from the status line at the top of Home.",
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
    val name = path.substringAfterLast('/').ifEmpty { "Top level" }

    Box {
        ListItem(
            // The folder's own name leads; the full path (long for app folders like WhatsApp's) sits underneath.
            headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
                Text(
                    if (parent.isEmpty()) countLabel(count) else "${countLabel(count)} · $parent",
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            },
            leadingContent = { Icon(Icons.Outlined.Folder, contentDescription = null) },
            trailingContent = {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More options for $name")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(EXCLUDE_LABEL) },
                            onClick = {
                                showMenu = false
                                onExclude()
                            },
                        )
                    }
                }
            },
            modifier = Modifier
                .heightIn(min = 56.dp)
                .combinedClickable(onLongClickLabel = EXCLUDE_LABEL, onLongClick = { showMenu = true }, onClick = onOpen),
        )
    }
}

private const val EXCLUDE_LABEL = "Stop searching this folder"

private fun countLabel(count: Long): String =
    if (count == 1L) "1 file" else "%,d files".format(count)
