package app.retriever.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retriever.R
import app.retriever.access.AccessService
import app.retriever.core.Kind
import app.retriever.core.SourceFile
import app.retriever.core.Store
import app.retriever.core.Words
import app.retriever.core.motion.MascotMood
import app.retriever.files.FilePressMenu
import app.retriever.nav.BrowseRoute
import app.retriever.nav.Route
import app.retriever.nav.StatusRoute
import app.retriever.starred.StarFailureEffect
import app.retriever.starred.starWithUndo
import app.retriever.ui.Format
import app.retriever.ui.ScrollingEffect
import app.retriever.ui.components.EmptyState
import app.retriever.ui.components.FileRow
import app.retriever.ui.components.Muted
import app.retriever.ui.components.PhotoMorph
import app.retriever.ui.components.ThumbCell
import app.retriever.ui.components.adaptiveColumns
import app.retriever.ui.components.contentPaddingFor
import app.retriever.ui.components.gutter
import app.retriever.viewer.Opener
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
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
        if (allFiles) "All files" else "Files Retriever can access"
    } else {
        route.title
    }

/** What the sort chips order by, said once under them, so "Newest" means the same here as the date on each row. */
internal fun sortCaption(sort: String): String = when (sort) {
    Store.Sort.LARGEST.name -> "Largest first"
    Store.Sort.NAME.name -> "By name, A to Z"
    else -> "Newest first · by date modified"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(route: BrowseRoute, onNavigate: (Route) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier, morph: PhotoMorph? = null) {
    val vm = koinViewModel<BrowseViewModel>()
    val access by koinInject<AccessService>().state.collectAsStateWithLifecycle()
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
    val people by vm.people.collectAsStateWithLifecycle()
    val person = people.firstOrNull { it.id == route.person }
    // Until the people load, the name Home opened them with.
    val title = if (route.person == null) browseTitle(route, ui.sort, access.allFiles) else person?.let { Words.personTitle(it.name) } ?: route.title
    val personFailed by vm.personFailed.collectAsStateWithLifecycle()
    LaunchedEffect(personFailed) {
        if (personFailed) {
            snackbar.showSnackbar("Couldn't change this person. Try again.")
            vm.personFailureShown()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                },
                actions = {
                    if (route.person != null) PersonActions(person, people.filter { it.id != route.person }, vm::rename, vm::sameAs)
                },
            )
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
            Muted(sortCaption(ui.sort), Modifier.padding(horizontal = 16.dp))
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
                        title = "Keep useful files close",
                        description = "Tap the star on a file to find it here.",
                        icon = R.drawable.ic_star_border,
                        mood = MascotMood.NO_MATCH,
                    )
                } else if (!filtered) {
                    EmptyState(
                        title = "Choose files to search",
                        description = "Add photos or a folder.",
                        icon = R.drawable.ic_folder_open,
                        mood = MascotMood.NO_MATCH,
                        action = "Manage access" to onManageAccess,
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
                // null is the width's own column count; a pinch picks another, kept across rotation.
                var chosen by rememberSaveable { mutableStateOf<Int?>(null) }
                val runs = remember(files, dateOf) { monthRuns(files, dateOf) }
                ScrollingEffect({ state.isScrollInProgress }, onScrolling)
                LoadMoreEffect(files.size, { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index }, onLoadMore)
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val direction = LocalLayoutDirection.current
                    val sides = listPadding.calculateLeftPadding(direction) + listPadding.calculateRightPadding(direction)
                    val width = maxWidth - 8.dp - sides
                    val columns = chosen ?: adaptiveColumns(width, 110.dp, 4.dp)
                    val cell = (width - 4.dp * (columns - 1)) / columns
                    val pinch by rememberUpdatedState { step: Int -> chosen = (columns + step).coerceIn(2, 8) }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        state = state,
                        contentPadding = listPadding,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp).pinchSteps { pinch(it) },
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
                                FilePressMenu(stored.file, starred = on, onStar = {
                                    onStar(stored.file, it)
                                }, notThisPerson = onNotThisPerson?.let { { it(stored) } }) { menu, actions ->
                                    ThumbCell(
                                        file = stored.file,
                                        onClick = { onOpen(stored.file) },
                                        requestSize = cell,
                                        readNote = Words.coverage(stored, ui.inside),
                                        onLongClick = menu,
                                        onLongClickLabel = "More options",
                                        modifier = actions,
                                        morph = morph,
                                    )
                                }
                            }
                        }
                        if (ui.error != null) item(span = { GridItemSpan(maxLineSpan) }, contentType = "error") { LoadMoreError(onRetry) }
                    }
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
                            FilePressMenu(stored.file, starred = on, onStar = { onStar(stored.file, it) }) { menu, actions ->
                                FileRow(
                                    file = stored.file,
                                    onClick = { onOpen(stored.file) },
                                    readNote = Words.coverage(stored, ui.inside),
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
    Store.Sort.NEWEST -> "Newest"
    Store.Sort.LARGEST -> "Largest"
    Store.Sort.NAME -> "Name"
}
