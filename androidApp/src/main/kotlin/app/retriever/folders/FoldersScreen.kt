package app.retriever.folders

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
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
import app.retriever.core.FolderNode
import app.retriever.core.Words
import app.retriever.core.motion.MascotMood
import app.retriever.files.folderRoute
import app.retriever.nav.Route
import app.retriever.ui.components.ConfirmDialog
import app.retriever.ui.components.EmptyState
import app.retriever.ui.components.contentPaddingFor
import app.retriever.ui.components.onRightClick
import app.retriever.ui.components.readingWidth
import org.koin.androidx.compose.koinViewModel

/** [onSearch] null hides the bar's Search, when the navigation rail has it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(
    onNavigate: (Route) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
    vm: FoldersViewModel = koinViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val (folders, loading, loadFailed, error) = ui
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
                title = { Text(Words.strings.folders) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = Words.strings.back)
                    }
                },
                actions = {
                    // Search is reachable from every place: here in the bar, or the rail's Search on a wide window.
                    onSearch?.let { IconButton(onClick = it) { Icon(painterResource(R.drawable.ic_search), contentDescription = Words.strings.searchAction) } }
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
                    title = if (loadFailed) Words.strings.foldersDidntLoad else Words.strings.noFoldersYet,
                    description = if (loadFailed) Words.strings.tryAgainInAMoment else Words.strings.foldersAppearPhone,
                    icon = R.drawable.ic_folder_open,
                    mood = if (loadFailed) MascotMood.TROUBLE else MascotMood.NO_MATCH,
                )
            }
        } else {
            LazyColumn(contentPadding = contentPaddingFor(padding), modifier = Modifier.fillMaxSize()) {
                items(folders, key = { it.path }) { node ->
                    FolderRow(node, onOpen = { onNavigate(folderRoute(node.path)) }, onExclude = { vm.exclude(node.path) })
                }
            }
        }
    }
}

/** A folder's name as a row shows it: a joined chain reads "media/com.whatsapp"; files at the root, "Top level". */
private val FolderNode.label: String get() = name.ifEmpty { Words.strings.topLevel }

/**
 * A folder in the tree: Folders' rows, and a folder's subfolders in Browse. Its menu stops searching it, after asking;
 * [onExclude] null offers no menu.
 */
@Composable
internal fun FolderRow(node: FolderNode, onOpen: () -> Unit, onExclude: (() -> Unit)? = null) {
    var showMenu by remember { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    val name = node.label
    if (confirming && onExclude != null) {
        ConfirmDialog(
            title = Words.strings.stopSearchingAsk(name),
            message = Words.strings.stopSearchingMessage(node.files, name),
            confirmLabel = Words.strings.stopSearching,
            onConfirm = {
                confirming = false
                onExclude()
            },
            onDismiss = { confirming = false },
        )
    }

    val menu = onExclude?.let { { showMenu = true } }

    ListItem(
        headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
        supportingContent = { Text(Words.strings.files(node.files), maxLines = 1) },
        leadingContent = { Icon(painterResource(R.drawable.ic_folder), contentDescription = null) },
        trailingContent = onExclude?.let {
            {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(painterResource(R.drawable.ic_more_vert), contentDescription = Words.strings.moreOptionsFor(name))
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(EXCLUDE_LABEL) },
                            onClick = {
                                showMenu = false
                                confirming = true
                            },
                        )
                    }
                }
            }
        },
        modifier = Modifier
            .readingWidth()
            .heightIn(min = 56.dp)
            .onRightClick { menu?.invoke() }
            .combinedClickable(onLongClickLabel = menu?.let { EXCLUDE_LABEL }, onLongClick = menu, onClick = onOpen),
    )
}

private val EXCLUDE_LABEL: String get() = Words.strings.stopSearchingThisFolder
