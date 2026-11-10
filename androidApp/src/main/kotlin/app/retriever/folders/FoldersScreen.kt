package app.retriever.folders

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retriever.R
import app.retriever.core.Words
import app.retriever.core.motion.MascotMood
import app.retriever.nav.BrowseRoute
import app.retriever.nav.Route
import app.retriever.ui.components.ConfirmDialog
import app.retriever.ui.components.EmptyState
import app.retriever.ui.components.contentPaddingFor
import app.retriever.ui.components.gutter
import app.retriever.ui.components.onRightClick
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(onNavigate: (Route) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier, vm: FoldersViewModel = koinViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val (folders, loading, loadFailed, error) = ui
    var confirming by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    // Shown first, cleared after: clearing first would change the key and dismiss the snackbar at once.
    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            vm.errorShown()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Folders") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (folders.isEmpty() && loading) {
            LinearProgressIndicator(Modifier.padding(padding).fillMaxWidth().padding(horizontal = 16.dp))
        } else if (folders.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = if (loadFailed) "Folders didn't load" else "No folders yet",
                    description = if (loadFailed) "Try again in a moment." else "Folders appear once Retriever has looked through your phone.",
                    icon = R.drawable.ic_folder_open,
                    mood = if (loadFailed) MascotMood.TROUBLE else MascotMood.NO_MATCH,
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
                            onExclude = { confirming = path },
                        )
                    }
                }
            }
        }
    }

    confirming?.let { path ->
        val count = folders.firstOrNull { it.first == path }?.second ?: return@let
        val name = path.substringAfterLast('/').ifEmpty { "Top level" }
        ConfirmDialog(
            title = "Stop searching $name?",
            message = "The ${Words.files(count)} in $name are removed from search now, and bringing them back means processing them all again. " +
                "You can bring it back from the status line at the top of Home.",
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

    ListItem(
        // The folder's own name leads; the full path (long for app folders like WhatsApp's) sits underneath.
        headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
            Text(
                if (parent.isEmpty()) Words.files(count) else "${Words.files(count)} · $parent",
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        },
        leadingContent = { Icon(painterResource(R.drawable.ic_folder), contentDescription = null) },
        trailingContent = {
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "More options for $name")
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
            .onRightClick { showMenu = true }
            .combinedClickable(onLongClickLabel = EXCLUDE_LABEL, onLongClick = { showMenu = true }, onClick = onOpen),
    )
}

private const val EXCLUDE_LABEL = "Stop searching this folder"
