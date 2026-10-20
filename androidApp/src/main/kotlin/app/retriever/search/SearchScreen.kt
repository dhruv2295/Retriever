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

package app.retriever.search

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retriever.R
import app.retriever.core.Kind
import app.retriever.core.SearchEngine
import app.retriever.core.SearchTiming
import app.retriever.core.SourceFile
import app.retriever.home.HomeScreen
import app.retriever.home.HomeUi
import app.retriever.home.HomeViewModel
import app.retriever.model.UpgradeFlow
import app.retriever.nav.Route
import app.retriever.nav.SettingsRoute
import app.retriever.starred.starWithUndo
import app.retriever.ui.ScrollingEffect
import app.retriever.ui.components.gutter
import app.retriever.viewer.Opener
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

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
    val onErrorShown: () -> Unit = {},
)

internal val visualKinds = setOf(Kind.PHOTO, Kind.SCREENSHOT, Kind.VIDEO)

@Composable
fun SearchScreen(
    onNavigate: (Route) -> Unit,
    vm: SearchViewModel = koinViewModel(),
    homeVm: HomeViewModel = koinViewModel(),
    upgrade: UpgradeFlow = koinInject(),
) {
    val search by vm.ui.collectAsStateWithLifecycle()
    val home by homeVm.ui.collectAsStateWithLifecycle()
    val stage by upgrade.stage.collectAsStateWithLifecycle()
    val ui = remember(search, stage) { search.copy(upgrade = stage) }
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshRecent() }
    // Remembered, so the result grid sees the same actions on every keystroke and can skip recomposing its tiles.
    val actions = remember(context, onNavigate) { vm.actions(context, onNavigate) }
    SearchContent(ui, home, actions) { padding, state, side, fill ->
        HomeScreen(ui.recent.takeIf { ui.recentLoaded }, actions, state, padding, side, fill)
    }
}

/** The screen's actions on this view model; the first run's try-it hosts the same search with the same ones. */
fun SearchViewModel.actions(context: Context, onNavigate: (Route) -> Unit) = SearchActions(
    onQuery = this::setQuery,
    onSubmit = { submit(it) },
    onChip = this::setChip,
    onFindSimilar = { findSimilar(it) },
    onPhoto = { searchPhoto(it.toString()) },
    onClearSimilar = this::clearSimilar,
    onRemoveRecent = { removeRecent(it) },
    onClearRecent = { clearRecent() },
    onRetry = this::retry,
    onOpenHit = { hit ->
        opened()
        // The words asked at the tap (none for a search by photo), so the reader highlights them.
        Opener.open(context, hit.file, hit.locator, onNavigate, fileId = hit.fileId, query = ui.value.question.text)
    },
    onOpenFile = { Opener.open(context, it, "", onNavigate) },
    onNavigate = onNavigate,
    onScrolling = this::setScrolling,
    onFocus = this::setFocused,
    onStarredOnly = this::setOnlyStarred,
    onStar = { file, on -> setStarred(file, on) },
    onWordsAsTyped = this::searchWordsAsTyped,
    onErrorShown = this::errorShown,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchContent(
    ui: SearchUi,
    home: HomeUi,
    actions: SearchActions,
    modifier: Modifier = Modifier,
    /** What sits beside the bar; null is the Settings button. The first run's try-it puts Skip here. */
    topBarAction: (@Composable () -> Unit)? = null,
    /** The Home page: the padding the top bar leaves, its list state, the side gutter, and fill (a past search into the field; true also runs it). */
    homeContent: @Composable (PaddingValues, LazyStaggeredGridState, Dp, (String, Boolean) -> Unit) -> Unit,
) {
    val bar = rememberSearchBarState()
    val text = rememberTextFieldState(ui.query)
    val scope = rememberCoroutineScope()
    val homeList = rememberLazyStaggeredGridState()
    val grid = rememberLazyGridState()
    // One host state for Home and the full-screen bar: only the one in front is seen.
    val snackbar = remember { SnackbarHostState() }
    // Consumed after it was shown: consuming first changes the key, which cancels the effect and takes the snackbar down.
    LaunchedEffect(ui.error) {
        ui.error?.let {
            snackbar.showSnackbar(it)
            actions.onErrorShown()
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
    ScrollingEffect({ homeList.isScrollInProgress || grid.isScrollInProgress }, actions.onScrolling)
    // The collapsed bar and the full-screen one are two fields: busy while either has focus, whichever event comes first.
    val focusing = remember { mutableStateSetOf<Any>() }
    LaunchedEffect(Unit) { snapshotFlow { focusing.isNotEmpty() }.collect { actions.onFocus(it) } }
    // Busy pauses indexing. The collectors above die with this screen before saying "not busy", and a focused field
    // stays focused in the background, so leaving the screen or the app says it here: else reading stalls until return.
    LifecycleStartEffect(Unit) {
        actions.onFocus(focusing.isNotEmpty())
        onStopOrDispose {
            actions.onFocus(false)
        }
    }

    // A search run from a chip (Home's, Try's) opens the bar on its results with nothing to type: the field gives up the
    // focus the bar hands it, which with the keyboard down would take the first Back, so one Back leaves the results.
    var picked by remember { mutableStateOf(false) }
    val closeSearch = {
        picked = false
        text.clearText()
        actions.onQuery("")
        actions.onClearSimilar()
        scope.launch { bar.animateToCollapsed() }
        Unit
    }
    BackHandler(enabled = text.text.isNotEmpty() || ui.similar != null || bar.currentValue == SearchBarValue.Expanded, onBack = closeSearch)

    // Read before the lambda, so it captures these two values and not the whole SearchUi.
    val similar = ui.similar
    val photoSearch = ui.canSearchByPhoto
    val inputField = @Composable { inDialog: Boolean ->
        // Read here: the expanded bar shows this field in its own dialog window.
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        // The bar focuses its field when it opens; a search by photo has nothing to type.
        LaunchedEffect(similar, bar.currentValue) {
            if (similar != null && bar.currentValue == SearchBarValue.Expanded) {
                focus.clearFocus()
                keyboard?.hide()
            }
        }
        val me = remember { Any() }
        DisposableEffect(me) { onDispose { focusing.remove(me) } }
        // The focus the opening bar hands its field after a chip's search is given straight back, once.
        var focused by remember { mutableStateOf(false) }
        LaunchedEffect(focused, picked) {
            if (inDialog && focused && picked) {
                picked = false
                focus.clearFocus()
                keyboard?.hide()
            }
        }
        SearchBarDefaults.InputField(
            modifier = Modifier.onFocusChanged {
                focused = it.hasFocus
                if (it.hasFocus) focusing.add(me) else focusing.remove(me)
            },
            textFieldState = text,
            searchBarState = bar,
            onSearch = {
                actions.onSubmit(it)
                // Done typing: a focused field with the keyboard down would take the first Back.
                focus.clearFocus()
                keyboard?.hide()
            },
            placeholder = { Text("Search inside your files", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = {
                if (bar.currentValue == SearchBarValue.Expanded) {
                    IconButton(onClick = closeSearch) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                } else {
                    Icon(painterResource(R.drawable.ic_search), contentDescription = null)
                }
            },
            trailingIcon = {
                if (text.text.isNotEmpty() || similar != null) {
                    IconButton(onClick = {
                        text.clearText()
                        actions.onClearSimilar()
                    }) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Clear search") }
                } else if (photoSearch) {
                    // Matching a photo needs the model a words-only phone leaves out, and on the first run photos to match.
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
            TopAppBar(title = {}, actions = {
                if (topBarAction != null) {
                    topBarAction()
                } else {
                    IconButton(onClick = {
                        actions.onNavigate(SettingsRoute)
                    }) { Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings") }
                }
            })
        },
        bottomBar = {
            // Docked at the bottom, in a thumb's reach; opaque, so Home's rows never show through it. safeDrawing holds
            // the navigation bar and the keyboard.
            Box(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                SearchBar(state = bar, inputField = { inputField(false) }, modifier = Modifier.widthIn(max = 808.dp).fillMaxWidth())
            }
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val use = { q: String, submit: Boolean ->
                text.setTextAndPlaceCursorAtEnd(q)
                picked = submit
                if (submit) actions.onSubmit(q)
                scope.launch { bar.animateToExpanded() }
                Unit
            }
            homeContent(padding, homeList, gutter(1200.dp), use)
        }
    }

    // Outside the Scaffold nothing sets the content colour, and the bar's dialog picks its status bar icons and its
    // results' text colour from it.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        ExpandedFullScreenSearchBar(state = bar, inputField = { inputField(true) }) {
            BackHandler(enabled = bar.currentValue == SearchBarValue.Expanded, onBack = closeSearch)
            LightStatusBarIconsOnDark()
            SearchPane(ui, home, actions, grid, snackbar, useText = { text.setTextAndPlaceCursorAtEnd(it) })
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
private fun GroupHeader(title: String, modifier: Modifier = Modifier) {
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
    home: HomeUi,
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
                if (heldFor(SearchTiming.SEARCHING_BAR_MS, ui.searching)) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val side = gutter()
                if (ui.query.isBlank() && ui.similar == null) {
                    Suggestions(ui.recent, home.suggestions.orEmpty(), actions, useText, side)
                } else {
                    Column { Results(ui, home, actions, star, grid, useText, side) }
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
            item(key = "try-header") { GroupHeader("Try searching") }
            item(key = "try") {
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (q in examples) SuggestionChip(onClick = { pick(q) }, label = { Text(q) })
                }
            }
            return@LazyColumn
        }
        item(key = "recent-header") { GroupHeader("Recent searches") }
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
