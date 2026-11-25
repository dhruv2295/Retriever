package app.retriever.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retriever.R
import app.retriever.access.AccessService
import app.retriever.core.DeviceWords
import app.retriever.core.FolderNode
import app.retriever.core.Kind
import app.retriever.core.SourceFile
import app.retriever.core.Store
import app.retriever.core.Words
import app.retriever.core.motion.MascotMood
import app.retriever.files.FilePressMenu
import app.retriever.files.Selection
import app.retriever.files.SelectionBar
import app.retriever.files.folderRoute
import app.retriever.files.picked
import app.retriever.folders.FolderRow
import app.retriever.nav.BrowseRoute
import app.retriever.nav.FoldersRoute
import app.retriever.nav.Route
import app.retriever.nav.StatusRoute
import app.retriever.starred.StarFailureEffect
import app.retriever.starred.starWithUndo
import app.retriever.ui.Format
import app.retriever.ui.ScrollingEffect
import app.retriever.ui.components.CappedCells
import app.retriever.ui.components.EmptyState
import app.retriever.ui.components.FileRow
import app.retriever.ui.components.Muted
import app.retriever.ui.components.PhotoMorph
import app.retriever.ui.components.ThumbCell
import app.retriever.ui.components.Thumbnails
import app.retriever.ui.components.contentPaddingFor
import app.retriever.ui.components.readingWidth
import app.retriever.viewer.Opener
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Start the next page when the last visible item is this close to the end. */
private const val LOAD_AHEAD = 20

private val GRID_KINDS = setOf(Kind.PHOTO, Kind.SCREENSHOT, Kind.VIDEO)

/**
 * The Large files entry is every file by size; sorted another way it is just all files. Starred keeps its name. Only
 * all-files access can call the list "All files": with less (limited Photos, a few folders) it is what Retriever can see.
 */
internal fun browseTitle(route: BrowseRoute, sort: String, allFiles: Boolean): String =
    if (route.kind == null && route.folder == null && !route.starredOnly && route.takenFrom == null && route.person == null && sort != "LARGEST") {
        if (allFiles) Words.strings.allFiles else Words.strings.filesRetrieverCanAccess
    } else {
        route.title
    }

/** What the sort chips order by, said once under them, so "Newest" means the same here as the date on each row. */
internal fun sortCaption(sort: String): String = when (sort) {
    Store.Sort.LARGEST.name -> Words.strings.sortLargestDetail
    Store.Sort.NAME.name -> Words.strings.sortNameDetail
    else -> Words.strings.sortNewestDetail
}

/** The grid's photo-morph origin: a cell and the viewer it opens share it. */
private const val BROWSE_ORIGIN = "browse"

/**
 * [onBack] null: a top-level place (the navigation rail's), with no Back arrow. [onSearch] null hides the bar's Search,
 * when the navigation rail has it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    route: BrowseRoute,
    onNavigate: (Route) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    morph: PhotoMorph? = null,
    onSearch: (() -> Unit)? = null,
) {
    val vm = koinViewModel<BrowseViewModel> { parametersOf(route) }
    val access by koinInject<AccessService>().state.collectAsStateWithLifecycle()
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val starred by vm.stars.starred.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val files by rememberUpdatedState(ui.files)
    // The list is mapped at the tap, so loading more rows never rebuilds the callback.
    val onOpen =
        remember(context, onNavigate) {
            { file: SourceFile -> Opener.open(context, file, navigate = onNavigate, siblings = files.map { it.file }, origin = BROWSE_ORIGIN) }
        }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // As on a search result: starring says so, with Undo, which also brings an unstarred file back to this list.
    val onStar = remember(vm, snackbar, scope) { starWithUndo({ file, on -> vm.stars.set(file.uri, on) }, snackbar, scope) }
    StarFailureEffect(vm.stars, snackbar)
    val people by vm.people.collectAsStateWithLifecycle()
    val person = people.firstOrNull { it.id == route.person }
    // Until the people load, the name Home opened them with.
    val title = if (route.person == null) browseTitle(route, ui.sort, access.allFiles) else person?.let { Words.personTitle(it.name) } ?: route.title
    val failure by vm.failure.collectAsStateWithLifecycle()
    LaunchedEffect(failure) {
        failure?.let {
            snackbar.showSnackbar(it)
            vm.failureShown()
        }
    }
    val subfolders by vm.subfolders.collectAsStateWithLifecycle()
    val picked by vm.selection.picked.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            val selecting = picked
            if (selecting != null) {
                SelectionBar(vm.selection, selecting, ui.files.map { it.file }, starred, onStar = { file, on -> vm.stars.set(file.uri, on) })
            } else {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = Words.strings.back)
                            }
                        }
                    },
                    actions = {
                        // Search is reachable from every place: here in the bar, or the rail's Search on a wide window.
                        onSearch?.let {
                            IconButton(onClick = it) { Icon(painterResource(R.drawable.ic_search), contentDescription = Words.strings.searchAction) }
                        }
                        if (ui.files.isNotEmpty()) {
                            IconButton(onClick = { vm.selection.start() }) {
                                Icon(painterResource(R.drawable.ic_check_circle_outlined), contentDescription = Words.strings.select)
                            }
                        }
                        if (route.person != null) PersonActions(person, people.filter { it.id != route.person }, vm::rename, vm::sameAs)
                    },
                )
            }
        },
    ) { padding ->
        BrowseContent(
            ui = ui,
            // A day, or a person, holds photos and videos together: a grid in its own order, so no sort chips.
            kind = if (route.takenFrom != null || route.person != null) Kind.PHOTO else Kind.entries.firstOrNull { it.name == route.kind },
            sortable = route.takenFrom == null && route.person == null,
            starredOnly = route.starredOnly,
            filtered = route.kind != null || route.folder != null || route.takenFrom != null || route.person != null,
            onNotThisPerson = if (route.person != null) vm::notThisPerson else null,
            // Month headers follow the list's own order: a person's by taken date, Newest by date modified.
            dateOf = when {
                route.person != null -> TAKEN_OR_MODIFIED
                route.takenFrom == null && !route.starredOnly && ui.sort == Store.Sort.NEWEST.name -> MODIFIED
                else -> null
            },
            onManageAccess = { onNavigate(StatusRoute) },
            padding = padding,
            onSort = vm::setSort,
            onLoadMore = vm::loadMore,
            onRetry = vm::retry,
            onScrolling = vm::setScrolling,
            onOpen = onOpen,
            starred = starred,
            onStar = onStar,
            morph = morph,
            folder = route.folder,
            subfolders = subfolders,
            onNavigate = onNavigate,
            picked = picked,
            selection = vm.selection,
            onShowFolder = { onNavigate(folderRoute(it.folder)) },
            onExcludeFolder = vm::exclude,
        )
    }
}

/** "All folders › DCIM › Camera": each folder above this one opens; the last, this one, is where you are. */
@Composable
private fun PathBar(folder: String, onNavigate: (Route) -> Unit, modifier: Modifier = Modifier) {
    val parts = folder.split('/')
    // Scrolled from the end, so a deep folder shows where you are first.
    Row(modifier.horizontalScroll(rememberScrollState(), reverseScrolling = true).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onNavigate(FoldersRoute) }) { Text(Words.strings.allFolders) }
        parts.forEachIndexed { i, part ->
            Icon(painterResource(R.drawable.ic_keyboard_arrow_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            val name = part.ifEmpty { Words.strings.topLevel }
            if (i == parts.lastIndex) {
                Text(name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp))
            } else {
                TextButton(onClick = { onNavigate(folderRoute(parts.take(i + 1).joinToString("/"))) }) { Text(name) }
            }
        }
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
    /** A kind, folder or day narrows the list: its emptiness is not an empty library. */
    filtered: Boolean = false,
    onManageAccess: () -> Unit = {},
    sortable: Boolean = true,
    starred: Set<String> = emptySet(),
    onStar: (SourceFile, Boolean) -> Unit = { _, _ -> },
    onRetry: () -> Unit = {},
    onScrolling: (Boolean) -> Unit = {},
    /** A person's photos: the menu can take one out of their group. */
    onNotThisPerson: ((Store.StoredFile) -> Unit)? = null,
    /** Where a tapped photo grows into the viewer from its cell. */
    morph: PhotoMorph? = null,
    /** The date a date-ordered grid is in, which splits it under month headers; null keeps it flat. */
    dateOf: ((Store.StoredFile) -> Long)? = null,
    /** A folder's own list: its path above, its [subfolders] (null until read) before its files. */
    folder: String? = null,
    subfolders: List<FolderNode>? = emptyList(),
    onNavigate: (Route) -> Unit = {},
    /** The picked files while selecting, else null; [selection] picks them. */
    picked: Set<String>? = null,
    selection: Selection? = null,
    /** Offers Show in folder, except for a file already in [folder]. */
    onShowFolder: ((SourceFile) -> Unit)? = null,
    /** Stop searching a subfolder; null offers no menu on them. */
    onExcludeFolder: ((String) -> Unit)? = null,
) {
    val files = ui.files
    val folders = subfolders.orEmpty()
    val nothing = files.isEmpty() && folders.isEmpty()
    // Rows keep a readable width on a wide window, and the chips and caption above them share its edges, so the page
    // is one column; a grid of pictures uses the whole width.
    val column = if (kind in GRID_KINDS) Modifier.fillMaxWidth() else Modifier.readingWidth()
    val tint = MaterialTheme.colorScheme.secondaryContainer

    // While selecting, a tap or long press picks; otherwise a tap opens and a long press opens the menu.
    fun tap(file: SourceFile) = if (picked != null && selection != null) selection.toggle(file.uri) else onOpen(file)
    fun press(file: SourceFile, menu: () -> Unit) = if (picked != null && selection != null) selection.toggle(file.uri) else menu()
    fun select(file: SourceFile) = selection?.takeIf { picked == null }?.let { { it.start(file.uri) } }
    fun showFolder(file: SourceFile) = onShowFolder?.takeIf { file.folder != folder }?.let { { it(file) } }
    Box(modifier) {
        Column(Modifier.padding(top = padding.calculateTopPadding())) {
            if (folder != null) PathBar(folder, onNavigate, column)
            // Starred is ordered by when you starred, so the sort chips would not apply.
            if (sortable && !starredOnly) {
                Row(
                    column.padding(horizontal = 16.dp, vertical = 8.dp),
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
                Muted(sortCaption(ui.sort), column.padding(horizontal = 16.dp))
            }

            if (nothing && ui.error != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        title = Words.strings.couldntLoadYourFiles,
                        description = Words.strings.wentWrongReadingThem,
                        icon = R.drawable.ic_folder_open,
                        mood = MascotMood.TROUBLE,
                        action = Words.TRY_AGAIN to onRetry,
                    )
                }
            } else if (nothing && (ui.loading || subfolders == null)) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            } else if (nothing) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (starredOnly) {
                        EmptyState(
                            title = Words.strings.keepUsefulFilesClose,
                            description = Words.strings.tapStarToFindHere,
                            icon = R.drawable.ic_star_border,
                            mood = MascotMood.NO_MATCH,
                        )
                    } else if (!filtered) {
                        EmptyState(
                            title = Words.strings.chooseFilesToSearch,
                            description = Words.strings.addPhotosOrFolder,
                            icon = R.drawable.ic_folder_open,
                            mood = MascotMood.NO_MATCH,
                            action = Words.strings.manageAccess to onManageAccess,
                        )
                    } else {
                        EmptyState(
                            title = Words.strings.nothingHereYet,
                            description = Words.strings.filesShowUpHere(DeviceWords.ANDROID),
                            icon = R.drawable.ic_folder_open,
                            mood = MascotMood.NO_MATCH,
                        )
                    }
                }
            } else {
                val listPadding = contentPaddingFor(padding, includeTop = false)
                if (kind in GRID_KINDS) {
                    val state = rememberLazyGridState()
                    // null is the width's own column count; a pinch picks another, kept across rotation.
                    var chosen by rememberSaveable { mutableStateOf<Int?>(null) }
                    var request by remember { mutableIntStateOf(0) }
                    val base = chosen?.let(GridCells::Fixed) ?: GridCells.Adaptive(110.dp)
                    val cells = remember(base) {
                        CappedCells(base) { request = Thumbnails.bucket(it) }
                    }
                    val runs = remember(files, dateOf) { monthRuns(files, dateOf) }
                    ScrollingEffect({ state.isScrollInProgress }, onScrolling)
                    LoadMoreEffect(files.size, { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index }, onLoadMore)
                    LazyVerticalGrid(
                        columns = cells,
                        state = state,
                        contentPadding = listPadding,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp).pinchSteps { chosen = (state.layoutInfo.maxSpan + it).coerceIn(2, 8) },
                    ) {
                        runs.forEach { (month, run) ->
                            if (month != null) {
                                stickyHeader(key = "m${run.first().id}", contentType = "month") {
                                    Text(
                                        month,
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.fillMaxWidth().background(
                                            MaterialTheme.colorScheme.surface,
                                        ).padding(horizontal = 4.dp, vertical = 8.dp),
                                    )
                                }
                            }
                            items(run, key = { it.id }, contentType = { "file" }) { stored ->
                                val on = stored.file.uri in starred
                                FilePressMenu(
                                    stored.file,
                                    starred = on,
                                    onStar = { onStar(stored.file, it) },
                                    notThisPerson = onNotThisPerson?.let { { it(stored) } },
                                    select = select(stored.file),
                                    showInFolder = showFolder(stored.file),
                                ) { menu, actions ->
                                    ThumbCell(
                                        file = stored.file,
                                        onClick = { tap(stored.file) },
                                        requestSize = with(LocalDensity.current) { request.toDp() },
                                        readNote = Words.coverage(stored, ui.inside),
                                        onLongClick = { press(stored.file, menu) },
                                        onLongClickLabel = Words.strings.moreOptions,
                                        modifier = actions,
                                        morph = morph,
                                        origin = BROWSE_ORIGIN,
                                        selected = picked?.let { stored.file.uri in it },
                                    )
                                }
                            }
                        }
                        if (ui.error != null) item(span = { GridItemSpan(maxLineSpan) }, contentType = "error") { LoadMoreError(onRetry) }
                    }
                } else {
                    val state = rememberLazyListState()
                    ScrollingEffect({ state.isScrollInProgress }, onScrolling)
                    LoadMoreEffect(files.size, { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index }, onLoadMore)
                    LazyColumn(
                        state = state,
                        contentPadding = listPadding,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(folders, key = { "f" + it.path }, contentType = { "folder" }) { node ->
                            FolderRow(node, onOpen = { onNavigate(folderRoute(node.path)) }, onExclude = onExcludeFolder?.let { { it(node.path) } })
                        }
                        items(files, key = { it.id }, contentType = { "file" }) { stored ->
                            val on = stored.file.uri in starred
                            val chosen = picked?.let { stored.file.uri in it }
                            FilePressMenu(
                                stored.file,
                                column,
                                starred = on,
                                onStar = { onStar(stored.file, it) },
                                select = select(stored.file),
                                showInFolder = showFolder(stored.file),
                            ) { menu, actions ->
                                FileRow(
                                    file = stored.file,
                                    onClick = { tap(stored.file) },
                                    readNote = Words.coverage(stored, ui.inside),
                                    leading = chosen?.let { { Checkbox(checked = it, onCheckedChange = null) } },
                                    onLongClick = { press(stored.file, menu) },
                                    onLongClickLabel = Words.strings.moreOptions,
                                    onClickLabel = when (chosen) {
                                        null -> Words.strings.openAction
                                        true -> Words.strings.deselect
                                        false -> Words.strings.select
                                    },
                                    modifier = if (chosen == null) actions else actions.picked(chosen, tint),
                                )
                            }
                        }
                        if (ui.error != null) item(contentType = "error") { LoadMoreError(onRetry, column) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadMoreError(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(Words.strings.couldntLoadMoreFiles, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onRetry) { Text(Words.TRY_AGAIN) }
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

private val TAKEN_OR_MODIFIED: (Store.StoredFile) -> Long = { it.file.takenAt ?: it.file.modifiedAt }
private val MODIFIED: (Store.StoredFile) -> Long = { it.file.modifiedAt }

/** [files] split into runs of one month each, labelled; one unlabelled run when [dateOf] is null. */
internal fun monthRuns(files: List<Store.StoredFile>, dateOf: ((Store.StoredFile) -> Long)?): List<Pair<String?, List<Store.StoredFile>>> {
    if (dateOf == null) return listOf(null to files)
    val zone = ZoneId.systemDefault()
    val runs = mutableListOf<Pair<YearMonth, MutableList<Store.StoredFile>>>()
    for (f in files) {
        val month = YearMonth.from(Instant.ofEpochMilli(dateOf(f)).atZone(zone))
        if (runs.lastOrNull()?.first == month) runs.last().second += f else runs += month to mutableListOf(f)
    }
    return runs.map { (_, run) -> Format.month(dateOf(run.first())) to run }
}

/**
 * Two fingers spreading or pinching step the grid one column fewer or more, once per gesture. Read in the Initial pass so
 * the grid's own scrolling does not take the gesture first; one finger is left alone.
 */
private fun Modifier.pinchSteps(onStep: (Int) -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        var zoom = 1f
        var stepped = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                zoom *= event.calculateZoom()
                if (!stepped && (zoom > 1.25f || zoom < 0.8f)) {
                    onStep(if (zoom > 1f) -1 else 1)
                    stepped = true
                }
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

private fun Store.Sort.label(): String = when (this) {
    Store.Sort.NEWEST -> Words.strings.sortNewest
    Store.Sort.LARGEST -> Words.strings.sortLargest
    Store.Sort.NAME -> Words.strings.sortName
}
