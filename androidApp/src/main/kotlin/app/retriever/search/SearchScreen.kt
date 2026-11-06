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
// Adapted from androidx compose material3 1.4.0 samples SearchBarSamples.kt SimpleSearchBarSample (Apache-2.0) and google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/smartalbum/SmartAlbumSearchScreen.kt results grid (Apache-2.0). Modified for Retriever: search on a screen of its own opened from a pill on Home, search by photo, recent searches, kind chips that search again, and a grid that mixes picture cells with full-width document rows keyed by file.

package app.retriever.search

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retriever.R
import app.retriever.core.Kind
import app.retriever.core.Retriever
import app.retriever.core.SearchEngine
import app.retriever.core.SearchTiming
import app.retriever.core.SourceFile
import app.retriever.core.Words
import app.retriever.home.HomeScreen
import app.retriever.home.HomeUi
import app.retriever.home.HomeViewModel
import app.retriever.model.UpgradeFlow
import app.retriever.nav.Route
import app.retriever.nav.SearchRoute
import app.retriever.nav.SettingsRoute
import app.retriever.starred.starWithUndo
import app.retriever.ui.ScrollingEffect
import app.retriever.ui.components.PhotoMorph
import app.retriever.ui.components.adaptiveCell
import app.retriever.ui.components.gutter
import app.retriever.viewer.Opener
import kotlinx.coroutines.flow.filterIsInstance
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/** What the search screen can ask for; every default does nothing, so a test passes only what it checks. */
data class SearchActions(
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
    /** A file and the tile list it was tapped in, which its photo viewer grows from. */
    val onOpenFile: (SourceFile, String) -> Unit = { _, _ -> },
    val onNavigate: (Route) -> Unit = {},
    val onScrolling: (Boolean) -> Unit = {},
    val onFocus: (Boolean) -> Unit = {},
    val onStarredOnly: (Boolean) -> Unit = {},
    val onStar: (SourceFile, Boolean) -> Unit = { _, _ -> },
    val onWordsAsTyped: () -> Unit = {},
    val onErrorShown: () -> Unit = {},
    /** Where a tapped photo grows into the viewer from its tile; null when it just opens. */
    val morph: PhotoMorph? = null,
)

internal val visualKinds = setOf(Kind.PHOTO, Kind.SCREENSHOT, Kind.VIDEO)

/** Home: its page, and the search pill that opens [SearchScreen]. */
@Composable
fun HomeHost(
    onNavigate: (Route) -> Unit,
    modifier: Modifier = Modifier,
    pillModifier: Modifier = Modifier,
    morph: PhotoMorph? = null,
    vm: SearchViewModel = koinViewModel(),
) {
    val search by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val actions = remember(context, onNavigate, morph) { vm.actions(context, onNavigate).copy(morph = morph) }
    val snackbar = remember { SnackbarHostState() }
    HomeShell(search, actions, onNavigate, modifier, pillModifier, snackbar = snackbar) { padding, state, side ->
        HomeScreen(actions, state, padding, side, snackbar)
    }
}

/**
 * Search on a screen of its own, on the nav host's back stack like any other: a viewer opened from it goes back to it,
 * and it goes back to where it was opened. [SearchRoute.scoped] keeps it to the first run's files.
 */
@Composable
fun SearchScreen(
    route: SearchRoute,
    onNavigate: (Route) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    morph: PhotoMorph? = null,
    vm: SearchViewModel = koinViewModel(),
    upgrade: UpgradeFlow = koinInject(),
    retriever: Retriever = koinInject(),
) {
    val home = if (route.scoped) {
        val ids by retriever.scopeIds.collectAsStateWithLifecycle()
        LaunchedEffect(ids) { vm.setScope(ids) }
        // What to try comes from the first run's files too.
        val suggestions by remember(ids) { retriever.suggestions(ids?.toSet().orEmpty()) }.collectAsStateWithLifecycle(null)
        HomeUi(suggestions = suggestions)
    } else {
        koinViewModel<HomeViewModel>().ui.collectAsStateWithLifecycle().value
    }
    val search by vm.ui.collectAsStateWithLifecycle()
    val stage by upgrade.stage.collectAsStateWithLifecycle()
    val ui = remember(search, stage) { search.copy(upgrade = stage) }
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshRecent() }
    // Remembered, so the result grid sees the same actions on every keystroke and can skip recomposing its tiles.
    val actions = remember(context, onNavigate, morph) { vm.actions(context, onNavigate).copy(morph = morph) }
    LaunchedEffect(vm) { vm.start(route) }
    SearchContent(ui, home, actions, onBack, modifier, query = route.query, typing = !route.run && route.photo == null)
}

/** The screen's actions on this view model; the first run's demo hosts the same search with the same ones. */
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
        Opener.open(context, hit.file, hit.locator, onNavigate, fileId = hit.fileId, query = ui.value.question.text, origin = SEARCH_ORIGIN)
    },
    onOpenFile = { file, origin -> Opener.open(context, file, "", onNavigate, origin = origin) },
    onNavigate = onNavigate,
    onScrolling = this::setScrolling,
    onFocus = this::setFocused,
    onStarredOnly = this::setOnlyStarred,
    onStar = { file, on -> setStarred(file, on) },
    onWordsAsTyped = this::searchWordsAsTyped,
    onErrorShown = this::errorShown,
)

/**
 * Home's frame, and the first run's demo's: a settings bar (none above a [footer]), the page, and the search pill docked
 * at the bottom in a thumb's reach. The pill holds no field: it opens [open]'s search, where the typing happens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeShell(
    ui: SearchUi,
    actions: SearchActions,
    open: (SearchRoute) -> Unit,
    modifier: Modifier = Modifier,
    pillModifier: Modifier = Modifier,
    /** The pill's words; the demo's names its files. */
    placeholder: String = Words.SEARCH_FIELD,
    /** Under the pill, pinned with it: the demo's Continue. */
    footer: (@Composable () -> Unit)? = null,
    /** Where the page says what it just did, with Undo. */
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    /** The page: the padding the bars leave, its list state, and the side gutter. */
    content: @Composable (PaddingValues, LazyStaggeredGridState, Dp) -> Unit,
) {
    val homeList = rememberLazyStaggeredGridState()
    ScrollingEffect({ homeList.isScrollInProgress }, actions.onScrolling)
    val pickPhoto = rememberLauncherForActivityResult(PickVisualMedia()) { uri -> if (uri != null) open(SearchRoute(photo = uri.toString())) }
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (footer == null) {
                TopAppBar(title = {}, actions = {
                    IconButton(onClick = {
                        actions.onNavigate(SettingsRoute)
                    }) { Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings") }
                })
            }
        },
        bottomBar = {
            // Opaque, so Home's rows never show through it. safeDrawing holds the navigation bar.
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Matching a photo needs the model a words-only phone leaves out, and on the first run photos to match.
                val photo = if (ui.canSearchByPhoto) ({ pickPhoto.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }) else null
                SearchPill(placeholder, photo, pillModifier) { open(SearchRoute()) }
                footer?.invoke()
            }
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize()) { content(padding, homeList, gutter(1200.dp)) }
    }
}

/** The collapsed search bar's look, as a button: focus and the keyboard belong to the search screen's field. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchPill(placeholder: String, onPhoto: (() -> Unit)?, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onOpen,
        shape = RectangleShape,
        color = SearchBarDefaults.colors().containerColor,
        modifier = Modifier.widthIn(max = 808.dp).fillMaxWidth().heightIn(min = SearchBarDefaults.InputFieldHeight)
            .clip(SearchBarDefaults.inputFieldShape).then(modifier),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_search), contentDescription = null, tint = muted)
            Text(
                placeholder,
                Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 8.dp),
                color = muted,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (onPhoto != null) {
                IconButton(onClick = onPhoto) { Icon(painterResource(R.drawable.ic_image_search), contentDescription = "Search with a photo") }
            }
        }
    }
}

/**
 * The search screen's body: the field on top, suggestions or results below. Back is the nav host's, as on every
 * screen. [typing]: the keyboard comes up on the way in (an empty search, or words to edit), once: coming back from a
 * viewer brings none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchContent(
    ui: SearchUi,
    home: HomeUi,
    actions: SearchActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    query: String = "",
    typing: Boolean = query.isBlank(),
) {
    val text = rememberTextFieldState(query)
    val grid = rememberLazyGridState()
    val snackbar = remember { SnackbarHostState() }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // Consumed after it was shown: consuming first changes the key, which cancels the effect and takes the snackbar down.
    LaunchedEffect(ui.error) {
        ui.error?.let {
            snackbar.showSnackbar(it)
            actions.onErrorShown()
        }
    }
    val pickPhoto = rememberLauncherForActivityResult(PickVisualMedia()) { uri -> if (uri != null) actions.onPhoto(uri) }
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
    ScrollingEffect({ grid.isScrollInProgress }, actions.onScrolling)
    var arrived by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!arrived) {
            arrived = true
            if (typing) focus.requestFocus()
        }
    }
    // Done typing: the keyboard goes, and the results are in full view.
    val done = {
        focusManager.clearFocus()
        keyboard?.hide()
        Unit
    }
    // The person's own drag, not the scroll to the top a new answer does while they type.
    val dismiss by rememberUpdatedState(done)
    LaunchedEffect(grid) { grid.interactionSource.interactions.filterIsInstance<DragInteraction.Start>().collect { dismiss() } }
    val similar = ui.similar
    Surface(modifier.fillMaxSize(), color = SearchBarDefaults.colors().containerColor) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            SearchBarDefaults.InputField(
                state = text,
                onSearch = {
                    actions.onSubmit(it)
                    done()
                },
                expanded = true,
                onExpandedChange = {},
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { actions.onFocus(it.hasFocus) },
                placeholder = { Text(Words.SEARCH_FIELD, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back") }
                },
                trailingIcon = {
                    if (text.text.isNotEmpty() || similar != null) {
                        IconButton(onClick = {
                            text.clearText()
                            actions.onClearSimilar()
                        }) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Clear search") }
                    } else if (ui.canSearchByPhoto) {
                        IconButton(onClick = { pickPhoto.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }) {
                            Icon(painterResource(R.drawable.ic_image_search), contentDescription = "Search with a photo")
                        }
                    }
                },
            )
            HorizontalDivider()
            SearchPane(ui, home, actions, grid, snackbar, useText = {
                text.setTextAndPlaceCursorAtEnd(it)
                done()
            })
        }
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

/** The search screen's body under its field: suggestions before anything is typed, results after. */
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
                    val cell = adaptiveCell(RESULT_CELL_MIN, RESULT_CELL_GAP, padding = (16.dp + side) * 2)
                    Column { Results(ui, home, actions, star, grid, useText, side, cell) }
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
            item(key = "try-header") { GroupHeader(Words.TRY_LABEL) }
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
