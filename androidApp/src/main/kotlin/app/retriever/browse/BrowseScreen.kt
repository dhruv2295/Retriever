package app.retriever.browse

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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retriever.R
import app.retriever.core.Kind
import app.retriever.core.SourceFile
import app.retriever.core.Store
import app.retriever.core.motion.MascotMood
import app.retriever.files.FilePressMenu
import app.retriever.nav.BrowseRoute
import app.retriever.nav.Route
import app.retriever.starred.StarFailureEffect
import app.retriever.starred.starWithUndo
import app.retriever.ui.ScrollingEffect
import app.retriever.ui.components.EmptyState
import app.retriever.ui.components.FileRow
import app.retriever.ui.components.ThumbCell
import app.retriever.ui.components.contentPaddingFor
import app.retriever.ui.components.coverageNote
import app.retriever.ui.components.gutter
import app.retriever.viewer.Opener
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.androidx.compose.koinViewModel

/** Start the next page when the last visible item is this close to the end. */
private const val LOAD_AHEAD = 20

private val GRID_KINDS = setOf(Kind.PHOTO, Kind.SCREENSHOT, Kind.VIDEO)

/** The Large files entry is every file by size; sorted another way it is just all files. Starred keeps its name. */
internal fun browseTitle(route: BrowseRoute, sort: String): String =
    if (route.kind == null && route.folder == null && !route.starredOnly && route.takenFrom == null && sort != "LARGEST") "All files" else route.title

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(route: BrowseRoute, onNavigate: (Route) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm = koinViewModel<BrowseViewModel>()
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val starred by vm.stars.starred.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val files by rememberUpdatedState(ui.files)
    // The list is mapped at the tap, so loading more rows never rebuilds the callback.
    val onOpen = remember(context, onNavigate) { { file: SourceFile -> Opener.open(context, file, navigate = onNavigate, siblings = files.map { it.file }) } }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // As on a search result: starring says so, with Undo, which also brings an unstarred file back to this list.
    val onStar = remember(vm, snackbar, scope) { starWithUndo({ file, on -> vm.stars.set(file.uri, on) }, snackbar, scope) }
    StarFailureEffect(vm.stars, snackbar)

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(browseTitle(route, ui.sort)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        BrowseContent(
            ui = ui,
            // A day holds photos and videos together: a grid, oldest first, so no sort chips.
            kind = if (route.takenFrom != null) Kind.PHOTO else Kind.entries.firstOrNull { it.name == route.kind },
            sortable = route.takenFrom == null,
            starredOnly = route.starredOnly,
            padding = padding,
            onSort = vm::setSort,
            onLoadMore = vm::loadMore,
            onRetry = vm::retry,
            onScrolling = vm::setScrolling,
            onOpen = onOpen,
            starred = starred,
            onStar = onStar,
        )
    }
}

/** Photos, screenshots and videos as a grid of thumbnails; everything else as rows. */
@Composable
fun BrowseContent(
    ui: BrowseUi,
    kind: Kind?,
    padding: PaddingValues,
    onSort: (String) -> Unit,
    onLoadMore: () -> Unit,
    onOpen: (SourceFile) -> Unit,
    modifier: Modifier = Modifier,
    starredOnly: Boolean = false,
    sortable: Boolean = true,
    starred: Set<String> = emptySet(),
    onStar: (SourceFile, Boolean) -> Unit = { _, _ -> },
    onRetry: () -> Unit = {},
    onScrolling: (Boolean) -> Unit = {},
) {
    val files = ui.files
    Column(modifier.padding(top = padding.calculateTopPadding())) {
        // Starred is ordered by when you starred, so the sort chips would not apply.
        if (sortable && !starredOnly) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Store.Sort.entries.forEach { option ->
                    FilterChip(
                        selected = ui.sort == option.name,
                        onClick = { onSort(option.name) },
                        label = { Text(option.label()) },
                    )
                }
            }
        }

        if (files.isEmpty() && ui.error != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = "Couldn't load your files",
                    description = "Something went wrong reading them. Give it another go.",
                    icon = R.drawable.ic_folder_open,
                    mood = MascotMood.TROUBLE,
                    action = "Try again" to onRetry,
                )
            }
        } else if (files.isEmpty() && ui.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        } else if (files.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (starredOnly) {
                    EmptyState(
                        title = "Nothing starred yet",
                        description = "Touch and hold a file, or tap the star while viewing it, to keep it here.",
                        icon = R.drawable.ic_star_border,
                        mood = MascotMood.NO_MATCH,
                    )
                } else {
                    EmptyState(
                        title = "Nothing here yet",
                        description = "Files show up here once Retriever finds them on your phone.",
                        icon = R.drawable.ic_folder_open,
                        mood = MascotMood.NO_MATCH,
                    )
                }
            }
        } else {
            val listPadding = contentPaddingFor(padding, includeTop = false)
            if (kind in GRID_KINDS) {
                val state = rememberLazyGridState()
                ScrollingEffect({ state.isScrollInProgress }, onScrolling)
                LoadMoreEffect(files.size, { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index }, onLoadMore)
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    state = state,
                    contentPadding = listPadding,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                ) {
                    items(files, key = { it.id }, contentType = { "file" }) { stored ->
                        val on = stored.file.uri in starred
                        StarMenu(stored.file, on, { onStar(stored.file, !on) }) { menu, actions ->
                            ThumbCell(
                                file = stored.file,
                                onClick = { onOpen(stored.file) },
                                readNote = coverageNote(stored),
                                onLongClick = menu,
                                onLongClickLabel = "More options",
                                modifier = actions,
                            )
                        }
                    }
                    if (ui.error != null) item(span = { GridItemSpan(maxLineSpan) }, contentType = "error") { LoadMoreError(onRetry) }
                }
            } else {
                val state = rememberLazyListState()
                ScrollingEffect({ state.isScrollInProgress }, onScrolling)
                LoadMoreEffect(files.size, { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index }, onLoadMore)
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = state,
                        contentPadding = contentPaddingFor(padding, side = gutter(), includeTop = false),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(files, key = { it.id }, contentType = { "file" }) { stored ->
                            val on = stored.file.uri in starred
                            StarMenu(stored.file, on, { onStar(stored.file, !on) }) { menu, actions ->
                                FileRow(
                                    file = stored.file,
                                    onClick = { onOpen(stored.file) },
                                    readNote = coverageNote(stored),
                                    onLongClick = menu,
                                    onLongClickLabel = "More options",
                                    modifier = actions,
                                )
                            }
                        }
                        if (ui.error != null) item(contentType = "error") { LoadMoreError(onRetry) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadMoreError(onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Couldn't load more files", color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onRetry) { Text("Try again") }
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

/** A file list's long-press menu, the shared one; a star here offers Undo through [toggle]. */
@Composable
private fun StarMenu(file: SourceFile, on: Boolean, toggle: () -> Unit, content: @Composable (menu: () -> Unit, actions: Modifier) -> Unit) {
    FilePressMenu(file, starred = on, onStar = { toggle() }, content = content)
}

private fun Store.Sort.label(): String = when (this) {
    Store.Sort.NEWEST -> "Newest"
    Store.Sort.LARGEST -> "Largest"
    Store.Sort.NAME -> "Name"
}
