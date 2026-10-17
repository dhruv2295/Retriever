/*
 * Copyright 2026 Google LLC
 * Copyright 2025 The Android Open Source Project (SearchBarSamples.kt, androidx/compose/material3)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Adapted from androidx compose material3 1.4.0 samples SearchBarSamples.kt SimpleSearchBarSample (Apache-2.0) and google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/smartalbum/SmartAlbumSearchScreen.kt results grid (Apache-2.0). Modified for Retriever: one shared input field for the collapsed and full-screen bar, search by photo, recent searches, kind chips that search again, and a grid that mixes picture cells with full-width document rows keyed by file.

package app.retriever

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retriever.core.IndexState
import app.retriever.core.Kind
import app.retriever.core.ReadExtent
import app.retriever.core.SearchEngine
import app.retriever.core.SourceFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/** What the search screen can ask for; every default does nothing, so a test passes only what it checks. */
class SearchActions(
    val onQuery: (String) -> Unit = {},
    val onSubmit: (String) -> Unit = {},
    val onChip: (Kind?) -> Unit = {},
    val onFindSimilar: (SourceFile) -> Unit = {},
    val onPhoto: (Uri) -> Unit = {},
    val onClearSimilar: () -> Unit = {},
    val onRemoveRecent: (String) -> Unit = {},
    val onClearRecent: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onOpenHit: (SearchEngine.Hit) -> Unit = {},
    val onOpenFile: (SourceFile) -> Unit = {},
    val onNavigate: (Route) -> Unit = {},
    val onScrolling: (Boolean) -> Unit = {},
    val onFocus: (Boolean) -> Unit = {},
    val onStarredOnly: (Boolean) -> Unit = {},
    val onStar: (SourceFile, Boolean) -> Unit = { _, _ -> },
    val onWordsAsTyped: () -> Unit = {},
    val onResume: () -> Unit = {},
    val onAllowAccess: () -> Unit = {},
    val onErrorShown: () -> Unit = {},
)

private val visualKinds = setOf(Kind.PHOTO, Kind.SCREENSHOT, Kind.VIDEO)

@Composable
fun SearchScreen(onNavigate: (Route) -> Unit, vm: SearchViewModel = koinViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val index by vm.indexState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // All-files access is switched in Settings, so the "can't see your files" card clears on return without a restart.
    LifecycleResumeEffect(vm) {
        vm.checkAccess()
        onPauseOrDispose {}
    }
    // Remembered, so the result grid sees the same actions on every keystroke and can skip recomposing its tiles.
    val actions = remember(vm, context, onNavigate) {
        SearchActions(
            onQuery = vm::setQuery,
            onSubmit = { vm.submit(it) },
            onChip = vm::setChip,
            onFindSimilar = { vm.findSimilar(it) },
            onPhoto = { vm.searchPhoto(it.toString()) },
            onClearSimilar = vm::clearSimilar,
            onRemoveRecent = { vm.removeRecent(it) },
            onClearRecent = { vm.clearRecent() },
            onRetry = vm::retry,
            onOpenHit = { hit ->
                vm.opened(hit)
                Opener.open(context, hit.file, hit.locator, onNavigate)
            },
            onOpenFile = { Opener.open(context, it, "", onNavigate) },
            onNavigate = onNavigate,
            onScrolling = vm::setScrolling,
            onFocus = vm::setFocused,
            onStarredOnly = vm::setOnlyStarred,
            onStar = { file, on -> vm.setStarred(file, on) },
            onWordsAsTyped = vm::searchWordsAsTyped,
            onResume = vm::resume,
            onAllowAccess = { openAllFilesSettings(context) },
            onErrorShown = vm::errorShown,
        )
    }
    SearchContent(ui.copy(charging = rememberCharging()), index, actions)
}

/** Whether the phone is on a charger, from the sticky battery broadcast, kept current while shown. */
@Composable
private fun rememberCharging(): Boolean {
    val context = LocalContext.current
    var charging by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            }
        }
        // The sticky value arrives at once through the return value, and again to the receiver.
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { receiver.onReceive(context, it) }
        onDispose { context.unregisterReceiver(receiver) }
    }
    return charging
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchContent(ui: SearchUi, index: IndexState, actions: SearchActions, modifier: Modifier = Modifier) {
    val bar = rememberSearchBarState()
    val text = rememberTextFieldState(ui.query)
    val scope = rememberCoroutineScope()
    val homeList = rememberLazyStaggeredGridState()
    val grid = rememberLazyGridState()
    // One host state for Home and the full-screen bar: only the one in front is seen.
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(ui.error) {
        ui.error?.let {
            actions.onErrorShown()
            snackbar.showSnackbar(it)
        }
    }
    val pickPhoto = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) {
            actions.onPhoto(uri)
            scope.launch { bar.animateToExpanded() }
        }
    }

    LaunchedEffect(text) { snapshotFlow { text.text.toString() }.collect { actions.onQuery(it) } }
    // A search by photo replaces the words.
    LaunchedEffect(ui.similar) { if (ui.similar != null) text.clearText() }
    // The grid keeps its place by item key, so a new answer sharing a key (a section header, a file) would open
    // part way down, past its best matches. Saveable, so coming back from a viewer keeps the place.
    var shownAnswer by rememberSaveable { mutableIntStateOf(ui.answer) }
    LaunchedEffect(ui.answer) {
        if (ui.answer != shownAnswer) {
            grid.scrollToItem(0)
            shownAnswer = ui.answer
        }
    }
    // A search handed in from outside (a shortcut) opens the bar with its words, once. Saveable, so a return from a viewer does not reopen it.
    var shownLaunch by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(ui.launched) {
        if (ui.launched > shownLaunch) {
            shownLaunch = ui.launched
            text.setTextAndPlaceCursorAtEnd(ui.query)
            bar.animateToExpanded()
        }
    }
    LaunchedEffect(homeList, grid) {
        snapshotFlow { homeList.isScrollInProgress || grid.isScrollInProgress }.collect { actions.onScrolling(it) }
    }
    // The collapsed bar and the full-screen one are two fields: busy while either has focus, whichever event comes first.
    val focusing = remember { mutableStateSetOf<Any>() }
    LaunchedEffect(Unit) { snapshotFlow { focusing.isNotEmpty() }.collect { actions.onFocus(it) } }
    // Busy pauses indexing. The collectors above die with this screen before saying "not busy", and a focused field
    // stays focused in the background, so leaving the screen or the app says it here: else reading stalls until return.
    LifecycleStartEffect(Unit) {
        actions.onFocus(focusing.isNotEmpty())
        onStopOrDispose {
            actions.onFocus(false)
            actions.onScrolling(false)
        }
    }

    val inputField = @Composable {
        // Read here: the expanded bar shows this field in its own dialog window.
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        // The bar focuses its field when it opens; a search by photo has nothing to type.
        LaunchedEffect(ui.similar, bar.currentValue) {
            if (ui.similar != null && bar.currentValue == SearchBarValue.Expanded) {
                focus.clearFocus()
                keyboard?.hide()
            }
        }
        val me = remember { Any() }
        DisposableEffect(me) { onDispose { focusing.remove(me) } }
        SearchBarDefaults.InputField(
            modifier = Modifier.onFocusChanged { if (it.hasFocus) focusing.add(me) else focusing.remove(me) },
            textFieldState = text,
            searchBarState = bar,
            onSearch = {
                actions.onSubmit(it)
                keyboard?.hide()
            },
            placeholder = { Text("Search inside your files", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = {
                if (bar.currentValue == SearchBarValue.Expanded) {
                    IconButton(onClick = { scope.launch { bar.animateToCollapsed() } }) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                } else {
                    Icon(painterResource(R.drawable.ic_search), contentDescription = null)
                }
            },
            trailingIcon = {
                if (text.text.isNotEmpty() || ui.similar != null) {
                    IconButton(onClick = {
                        text.clearText()
                        actions.onClearSimilar()
                    }) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Clear search") }
                } else if (!ui.textOnly) {
                    // Matching a photo needs the model a words-only phone leaves out.
                    IconButton(onClick = { pickPhoto.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }) {
                        Icon(painterResource(R.drawable.ic_image_search), contentDescription = "Search with a photo")
                    }
                }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            // Opaque, as a top app bar is: Home scrolls under it, and its rows must not show through the status bar.
            Box(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                    .windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(Modifier.widthIn(max = 808.dp + 48.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SearchBar(state = bar, inputField = inputField, modifier = Modifier.weight(1f))
                    IconButton(onClick = { actions.onNavigate(SettingsRoute) }) { Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings") }
                }
            }
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val use = { q: String, submit: Boolean ->
                text.setTextAndPlaceCursorAtEnd(q)
                if (submit) actions.onSubmit(q)
                scope.launch { bar.animateToExpanded() }
                Unit
            }
            HomeBody(ui, index, actions, homeList, padding, gutter(1200.dp), use)
        }
    }

    // Outside the Scaffold nothing sets the content colour, and the bar's dialog picks its status bar icons and its
    // results' text colour from it.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        ExpandedFullScreenSearchBar(state = bar, inputField = inputField) {
            LightStatusBarIconsOnDark()
            SearchPane(ui, actions, grid, snackbar, useText = { text.setTextAndPlaceCursorAtEnd(it) })
        }
    }
}

/** The bar's dialog is its own window: enableEdgeToEdge() set the status bar icons of the activity's only. */
@Composable
private fun LightStatusBarIconsOnDark() {
    val view = LocalView.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.let { WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = !dark }
    }
}

@Composable
private fun Header(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp).semantics { heading() },
    )
}

// --- Full-screen search ---

/** The full-screen bar's body: suggestions before anything is typed, results after. */
@Composable
internal fun SearchPane(
    ui: SearchUi,
    actions: SearchActions,
    grid: LazyGridState = rememberLazyGridState(),
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    useText: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    // Starring says so, with a way back.
    val star = remember(actions, snackbar, scope) { starWithUndo(actions.onStar, snackbar, scope) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(4.dp)) {
                if (ui.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val side = gutter()
                if (ui.query.isBlank() && ui.similar == null) {
                    Suggestions(ui.recent, ui.suggestions, actions, useText, side)
                } else {
                    Column { Results(ui, actions, star, grid, useText, side) }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)))
    }
}

@Composable
private fun Suggestions(recent: List<String>, examples: List<String>, actions: SearchActions, useText: (String) -> Unit, side: Dp) {
    val pick = { q: String ->
        useText(q)
        actions.onSubmit(q)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = side, end = side, bottom = 24.dp)) {
        if (recent.isEmpty()) {
            if (examples.isEmpty()) return@LazyColumn
            item(key = "try-header") { Header("Try searching") }
            item(key = "try") {
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (q in examples) SuggestionChip(onClick = { pick(q) }, label = { Text(q) })
                }
            }
            return@LazyColumn
        }
        item(key = "recent-header") { Header("Recent searches") }
        items(recent, key = { it }) { q ->
            ListItem(
                headlineContent = { Text(q, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingContent = { Icon(painterResource(R.drawable.ic_history), contentDescription = null) },
                trailingContent = {
                    IconButton(onClick = { actions.onRemoveRecent(q) }) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = "Remove $q from history")
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.animateItem().clickable(onClickLabel = "Search") { pick(q) },
            )
        }
        item(key = "clear") {
            TextButton(onClick = actions.onClearRecent, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Clear history") }
        }
    }
}

@Composable
private fun Results(ui: SearchUi, actions: SearchActions, star: (SourceFile, Boolean) -> Unit, grid: LazyGridState, useText: (String) -> Unit, side: Dp) {
    val q = ui.query.trim()
    val edge = 16.dp + side
    ui.similar?.let { s ->
        InputChip(
            selected = true,
            onClick = actions.onClearSimilar,
            label = { Text("Like ${s.label}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            avatar = s.thumb?.let { thumb ->
                {
                    Image(
                        thumb,
                        contentDescription = "The photo you're searching with",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(InputChipDefaults.AvatarSize).clip(CircleShape),
                    )
                }
            },
            trailingIcon = { Icon(painterResource(R.drawable.ic_close), contentDescription = "Stop searching by photo", Modifier.size(18.dp)) },
            modifier = Modifier.padding(horizontal = edge),
        )
    }
    // The hits were found for these very words; while newer ones wait, what was understood would be of the old ones.
    val parsed = ui.parsed?.takeIf { ui.similar == null && ui.hits != null && ui.hitsFor == q && it.understood.isNotEmpty() }
    // Kinds the words asked for show as picked chips too, so the row never says "All" over filtered results.
    val wordKinds = parsed?.kinds.orEmpty()
    val kinds = Kind.entries.filter { (ui.counts?.get(it) ?: 0L) > 0L }
    val canStar = ui.starred > 0 || ui.onlyStarred
    if (kinds.isNotEmpty() || canStar) {
        LazyRow(contentPadding = PaddingValues(horizontal = edge), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "all") {
                KindChip("All", ui.chip == null && !ui.onlyStarred && wordKinds.isEmpty()) {
                    actions.onChip(null)
                    actions.onStarredOnly(false)
                    if (wordKinds.isNotEmpty()) actions.onWordsAsTyped()
                }
            }
            if (canStar) item(key = "starred") { KindChip("Starred", ui.onlyStarred) { actions.onStarredOnly(!ui.onlyStarred) } }
            items(kinds, key = { it.name }) { k -> KindChip(Format.kindLabelPlural(k), ui.chip == k || k in wordKinds) { actions.onChip(k) } }
        }
    }
    parsed?.let {
        InputChip(
            selected = true,
            onClick = actions.onWordsAsTyped,
            label = { Text(it.understood.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { Icon(painterResource(R.drawable.ic_close), contentDescription = "Search the words as typed instead", Modifier.size(18.dp)) },
            modifier = Modifier.padding(horizontal = edge),
        )
        if (ui.relaxed && ui.hits.orEmpty().isNotEmpty()) {
            Text(it.relaxedNote(Format.scope(ui.chip, ui.onlyStarred)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = edge, vertical = 8.dp))
        }
    }
    val note = when {
        ui.similar != null && !ui.vectorLane -> "Search by photo works once setup finishes."
        !ui.setUp -> "Matching file names only until setup finishes."
        !ui.vectorLane && ui.textOnly -> "Retriever finds files here by their names and the words inside them."
        !ui.vectorLane && ui.modelMissing -> "Matching names and words. Download the search model again in Settings to search by meaning."
        !ui.vectorLane -> "Matching exact words for now. Results by meaning will be back shortly."
        else -> null
    }
    note?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = edge, vertical = 8.dp))
    }
    val hits = ui.hits
    // The words changed but haven't been searched yet: the hits still answer the old ones.
    val stale = ui.similar == null && ui.hitsFor != q
    val chip = ui.chip
    val searchAll = {
        actions.onChip(null)
        actions.onStarredOnly(false)
    }
    when {
        ui.failed -> Message {
            EmptyState("Search hit a snag", "Something went wrong. Try again.", R.drawable.ic_warning_amber, action = "Try again" to actions.onRetry)
        }
        hits == null -> Unit
        hits.isEmpty() && stale -> Unit
        hits.isEmpty() && (chip != null || ui.onlyStarred) -> Message {
            val where = Format.scope(chip, ui.onlyStarred)
            EmptyState(
                "No matches in your $where",
                if (ui.similar != null) "Nothing like this photo there." else "Nothing for “$q” there.",
                R.drawable.ic_search_off,
                action = "Search all files" to searchAll,
                art = R.drawable.illus_empty,
            )
        }
        hits.isEmpty() && ui.similar != null -> if (ui.vectorLane) {
            Message { EmptyState("Nothing like this yet", "Try another photo.", R.drawable.ic_image_search, art = R.drawable.illus_empty) }
        }
        hits.isEmpty() -> Message {
            val words = q.split(Regex("\\s+"))
            val total = ui.counts?.values?.sum() ?: 0L
            EmptyState(
                "No matches for “$q”",
                if (ui.waiting > 0) {
                    "Retriever has read ${Format.count(ui.searchable)} of ${Format.count(total)} files so far. Try again later, or try other words."
                } else {
                    "Try other or fewer words."
                },
                R.drawable.ic_search_off,
                action = if (words.size >= 2) "Try fewer words" to { useText(words.dropLast(1).joinToString(" ")) } else null,
                art = R.drawable.illus_empty,
            )
        }
        else -> {
            val act = remember(actions, ui.textOnly, ui.starredUris, star) {
                HitActions(actions.onOpenHit, actions.onFindSimilar.takeUnless { ui.textOnly }, ui.starredUris, star)
            }
            // The quick answer from names is complete as far as it goes; only a wait for the first one dims the rows.
            ResultGrid(hits, ui.strongCount, ui.extents, ui.hitsFor.ifEmpty { null }, (ui.searching && !ui.nameOnly) || stale, grid, edge, act)
        }
    }
}

/** Stars or unstars a file and says so in [snackbar], with Undo. */
internal fun starWithUndo(onStar: (SourceFile, Boolean) -> Unit, snackbar: SnackbarHostState, scope: CoroutineScope): (SourceFile, Boolean) -> Unit = { file, on ->
    onStar(file, on)
    scope.launch {
        snackbar.currentSnackbarData?.dismiss()
        val said = snackbar.showSnackbar(if (on) "Starred" else "Star removed", "Undo", duration = SnackbarDuration.Short)
        if (said == SnackbarResult.ActionPerformed) onStar(file, !on)
    }
}

/** What a result can do: open, find similar (null when the phone cannot), and be starred. */
private class HitActions(
    val open: (SearchEngine.Hit) -> Unit,
    val findSimilar: ((SourceFile) -> Unit)?,
    val starred: Set<String>,
    val star: (SourceFile, Boolean) -> Unit,
)

/** [content] is a result; a long press on it opens a menu to star it, and to find similar when [similar]. */
@Composable
private fun HitMenu(file: SourceFile, act: HitActions, similar: Boolean, modifier: Modifier = Modifier, content: @Composable (onLongClick: () -> Unit) -> Unit) {
    PressMenu(content, modifier) { dismiss ->
        val on = file.uri in act.starred
        DropdownMenuItem(
            text = { Text(if (on) "Remove star" else "Star") },
            leadingIcon = { Icon(painterResource(if (on) R.drawable.ic_star_border else R.drawable.ic_star), contentDescription = null) },
            onClick = {
                dismiss()
                act.star(file, !on)
            },
        )
        if (similar) {
            DropdownMenuItem(
                text = { Text("Find similar") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_image_search), contentDescription = null) },
                onClick = {
                    dismiss()
                    act.findSimilar?.invoke(file)
                },
            )
        }
    }
}

/** [content] with a long-press menu that opens where the finger went down, not at the row's left edge. */
@Composable
internal fun PressMenu(
    content: @Composable (onLongClick: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    menu: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var press by remember { mutableStateOf(IntOffset.Zero) }
    // The Initial pass only watches, so the row's own click and long press still get the touch.
    Box(modifier.pointerInput(Unit) { awaitEachGesture { press = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position.round() } }) {
        content { open = true }
        // A menu hangs from its anchor's bottom-left corner; an anchor of no size at the press puts that corner there.
        Box(Modifier.offset { press }) {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) { menu { open = false } }
        }
    }
}

@Composable
private fun KindChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(painterResource(R.drawable.ic_check), contentDescription = null, Modifier.size(18.dp)) }
        } else {
            null
        },
    )
}

@Composable
private fun Message(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 32.dp), contentAlignment = Alignment.TopCenter) { content() }
}

@Composable
private fun ResultGrid(
    hits: List<SearchEngine.Hit>,
    strongCount: Int,
    extents: Map<Long, ReadExtent>,
    query: String?,
    searching: Boolean,
    grid: LazyGridState,
    edge: Dp,
    act: HitActions,
) {
    val strong = hits.take(strongCount)
    val weak = hits.drop(strongCount)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        state = grid,
        contentPadding = PaddingValues(start = edge, end = edge, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().alpha(if (searching) 0.6f else 1f),
    ) {
        if (strong.isEmpty() && query != null) {
            item(key = "nothing-well", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Nothing matches “$query” well", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "These are the closest files, but they may not be what you're looking for.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        sections(strong, extents, query, titled = true, act)
        if (weak.isNotEmpty()) {
            sectionHeader("weak", "Less likely matches")
            sections(weak, extents, query, titled = false, act)
        }
    }
}

/**
 * Pictures and documents of [hits] in rank order as far as a grid allows: the documents ranked above the best picture,
 * then the pictures, then the other documents. Neither one strong document nor one strong photo buries the rest of
 * the other kind. [titled] gives each its header.
 */
private fun LazyGridScope.sections(
    hits: List<SearchEngine.Hit>,
    extents: Map<Long, ReadExtent>,
    query: String?,
    titled: Boolean,
    act: HitActions,
) {
    val (recordings, others) = hits.partition { it.file.kind == Kind.AUDIO }
    val (lead, media, rest) = sectionsOf(others)
    docSection(lead, extents, query, titled, act)
    mediaSection(media, extents, titled, act)
    if (lead.isEmpty()) {
        docSection(rest, extents, query, titled, act)
    } else {
        docSection(rest, extents, query, titled, act, "docs-more", "More documents")
    }
    docSection(recordings, extents, query, titled, act, "recordings", "Recordings")
}

/** The documents ranked above the first picture, the pictures, and the documents after it. */
internal fun sectionsOf(hits: List<SearchEngine.Hit>): Triple<List<SearchEngine.Hit>, List<SearchEngine.Hit>, List<SearchEngine.Hit>> {
    val lead = hits.takeWhile { !inGrid(it) }
    val (media, rest) = hits.drop(lead.size).partition(::inGrid)
    return Triple(lead, media, rest)
}

/** Pictures go in the grid, except a screenshot matched by its words: its row shows them. */
private fun inGrid(hit: SearchEngine.Hit): Boolean =
    hit.file.kind in visualKinds && !(hit.file.kind == Kind.SCREENSHOT && !hit.snippet.isNullOrBlank())

private fun LazyGridScope.sectionHeader(key: String, title: String) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.animateItem().padding(top = 12.dp).semantics { heading() },
        )
    }
}

private fun LazyGridScope.mediaSection(
    hits: List<SearchEngine.Hit>,
    extents: Map<Long, ReadExtent>,
    titled: Boolean,
    act: HitActions,
) {
    if (hits.isEmpty()) return
    if (titled) sectionHeader("media", "Photos and videos")
    items(hits, key = { it.fileId }) { hit ->
        HitMenu(hit.file, act, similar = hit.file.kind != Kind.VIDEO && act.findSimilar != null, Modifier.animateItem()) { menu ->
            ThumbCell(
                hit.file,
                onClick = { act.open(hit) },
                locator = hit.locator,
                readNote = Format.readNote(extents[hit.fileId]),
                onLongClick = menu,
                onLongClickLabel = "More options",
            )
        }
    }
}

private fun LazyGridScope.docSection(
    hits: List<SearchEngine.Hit>,
    extents: Map<Long, ReadExtent>,
    query: String?,
    titled: Boolean,
    act: HitActions,
    headerKey: String = "docs",
    header: String = "Documents and more",
) {
    if (hits.isEmpty()) return
    if (titled) sectionHeader(headerKey, header)
    items(hits, key = { it.fileId }, span = { GridItemSpan(maxLineSpan) }) { hit ->
        HitMenu(hit.file, act, similar = hit.file.kind == Kind.SCREENSHOT && act.findSimilar != null, Modifier.animateItem().bleed(16.dp)) { menu ->
            FileRow(
                hit.file,
                snippet = hit.snippet,
                locator = hit.locator,
                // Bold the words only where the file was found by its name.
                query = query.takeIf { hit.byName },
                readNote = Format.readNote(extents[hit.fileId]),
                onLongClick = menu,
                onLongClickLabel = "More options",
                onClick = { act.open(hit) },
            )
        }
    }
}

/** Widens a full-line grid item past the grid's side padding, so a row's own padding lines it up with the cells. */
private fun Modifier.bleed(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = (by * 2).roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}
