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

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retriever.core.IndexState
import app.retriever.core.Kind
import app.retriever.core.SearchEngine
import app.retriever.core.SourceFile
import app.retriever.core.android.ImageBytes
import kotlinx.coroutines.launch

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
    val onNavigate: (Any) -> Unit = {},
    val onScrolling: (Boolean) -> Unit = {},
)

private val visualKinds = setOf(Kind.PHOTO, Kind.SCREENSHOT, Kind.VIDEO)
private val browseKinds = listOf(Kind.PHOTO, Kind.SCREENSHOT, Kind.VIDEO, Kind.PDF, Kind.DOC, Kind.AUDIO)
private val examples = listOf("wifi password", "electricity bill", "passport photo", "notes from the meeting")

@Composable
fun SearchScreen(onNavigate: (Any) -> Unit) {
    val vm = appViewModel { g -> SearchViewModel(g.retriever) { uri -> ImageBytes.jpeg(g.app, Uri.parse(uri), 1024) } }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val index by vm.indexState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SearchContent(
        ui,
        index,
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
            onScrolling = vm::setUiBusy,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchContent(ui: SearchUi, index: IndexState, actions: SearchActions) {
    val bar = rememberSearchBarState()
    val text = rememberTextFieldState(ui.query)
    val scope = rememberCoroutineScope()
    val homeList = rememberLazyListState()
    val grid = rememberLazyGridState()
    val pickPhoto = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) {
            actions.onPhoto(uri)
            scope.launch { bar.animateToExpanded() }
        }
    }

    LaunchedEffect(text) { snapshotFlow { text.text.toString() }.collect { actions.onQuery(it) } }
    // A search by photo replaces the words.
    LaunchedEffect(ui.similar) { if (ui.similar != null) text.clearText() }
    LaunchedEffect(homeList, grid) {
        snapshotFlow { homeList.isScrollInProgress || grid.isScrollInProgress }.collect { actions.onScrolling(it) }
    }
    DisposableEffect(Unit) { onDispose { actions.onScrolling(false) } }

    val inputField = @Composable {
        // Read here: the expanded bar shows this field in its own dialog window.
        val keyboard = LocalSoftwareKeyboardController.current
        SearchBarDefaults.InputField(
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                } else {
                    Icon(Icons.Filled.Search, contentDescription = null)
                }
            },
            trailingIcon = {
                if (text.text.isNotEmpty() || ui.similar != null) {
                    IconButton(onClick = {
                        text.clearText()
                        actions.onClearSimilar()
                    }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
                } else {
                    IconButton(onClick = { pickPhoto.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }) {
                        Icon(Icons.Outlined.ImageSearch, contentDescription = "Search with a photo")
                    }
                }
            },
        )
    }

    Scaffold(
        topBar = {
            Box(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                SearchBar(state = bar, inputField = inputField, modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp))
            }
        },
    ) { padding ->
        HomeBody(ui, index, actions, homeList, padding)
    }

    ExpandedFullScreenSearchBar(state = bar, inputField = inputField) {
        SearchPane(ui, actions, grid, useText = { text.setTextAndPlaceCursorAtEnd(it) })
    }
}

// --- Home ---

@Composable
private fun HomeBody(ui: SearchUi, index: IndexState, actions: SearchActions, state: LazyListState, padding: PaddingValues) {
    val counts = ui.counts
    LazyColumn(state = state, contentPadding = contentPaddingFor(padding), modifier = Modifier.fillMaxSize()) {
        item(key = "status") { StatusLine(ui, index, actions.onNavigate) }
        // Not loaded yet: show nothing rather than flash the first-run message over a full index.
        if (counts == null) return@LazyColumn
        if (counts.values.sum() == 0L) {
            item(key = "looking") {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Image(painterResource(R.drawable.illus_reading), contentDescription = null, modifier = Modifier.widthIn(max = 240.dp).fillMaxWidth(0.7f))
                    Text("Retriever is looking for your files", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Text(
                        "This takes a minute the first time.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            return@LazyColumn
        }
        item(key = "browse-header") { Header("Browse") }
        item(key = "browse") {
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (kind in browseKinds) {
                    val n = counts[kind] ?: 0L
                    if (n == 0L) continue
                    AssistChip(
                        onClick = { actions.onNavigate(BrowseRoute(kind = kind.name, title = Format.kindLabelPlural(kind))) },
                        label = { Text("${Format.kindLabelPlural(kind)} · ${Format.count(n)}", style = MaterialTheme.typography.labelLarge.tabular()) },
                        leadingIcon = { Icon(kindIcon(kind), contentDescription = null, Modifier.size(18.dp)) },
                    )
                }
            }
        }
        if (ui.recentFiles.isNotEmpty()) {
            item(key = "recent-header") { Header("Recent files") }
            item(key = "recent") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ui.recentFiles, key = { it.uri }) { file ->
                        if (file.kind in visualKinds) {
                            ThumbCell(file, onClick = { actions.onOpenFile(file) }, modifier = Modifier.width(96.dp))
                        } else {
                            FileCard(file) { actions.onOpenFile(file) }
                        }
                    }
                }
            }
        }
        item(key = "tidy-header") { Header("Tidy up") }
        item(key = "tidy") {
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { actions.onNavigate(FoldersRoute) },
                    label = { Text("Folders") },
                    leadingIcon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null, Modifier.size(18.dp)) },
                )
                AssistChip(
                    onClick = { actions.onNavigate(BrowseRoute(sort = "LARGEST", title = "Large files")) },
                    label = { Text("Large files") },
                    leadingIcon = { Icon(Icons.Outlined.Storage, contentDescription = null, Modifier.size(18.dp)) },
                )
                AssistChip(
                    onClick = { actions.onNavigate(DuplicatesRoute) },
                    label = { Text("Duplicates") },
                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null, Modifier.size(18.dp)) },
                )
            }
        }
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

/** A file with no picture: its kind's icon over its name, the same width as a picture cell. */
@Composable
private fun FileCard(file: SourceFile, onClick: () -> Unit) {
    Column(
        Modifier
            .width(96.dp)
            .heightIn(min = 96.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClickLabel = "Open", onClick = onClick)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(kindIcon(file.kind), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(file.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** One slim row saying how far Retriever has got. Always there once counted: it is the way into the status screen. */
@Composable
private fun StatusLine(ui: SearchUi, index: IndexState, onNavigate: (Any) -> Unit) {
    val total = ui.counts?.values?.sum() ?: 0L
    val of = "${Format.count(ui.searchable)} of ${Format.count(total)}"
    val (line, target) = when {
        !ui.setUp -> "Finishing setup. For now Retriever matches file names." to SetupRoute
        index.running && index.pendingAtStart == 0L -> "Looking for your files…" to StatusRoute
        index.running -> "Reading your files · $of" to StatusRoute
        ui.counts == null -> return
        ui.waiting > 0 -> "$of files searchable" + (waitingCopy(index.lastStop)?.let { " · $it" } ?: "") to StatusRoute
        else -> "All caught up · ${Format.count(ui.searchable)} files searchable" to StatusRoute
    }
    val reading = ui.setUp && index.running && index.pendingAtStart > 0L
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            Modifier
                .clickable(role = Role.Button, onClickLabel = if (target == SetupRoute) "Finish setup" else "Show details") { onNavigate(target) }
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(line, style = MaterialTheme.typography.bodyMedium.tabular())
                if (reading) {
                    LinearProgressIndicator(
                        progress = { if (total > 0) ui.searchable.toFloat() / total else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    index.etaMinutes?.let { eta ->
                        Text(
                            Format.eta(eta).replaceFirstChar { it.uppercase() } + "." + if (eta >= 60) " Faster while charging." else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// --- Full-screen search ---

/** The full-screen bar's body: suggestions before anything is typed, results after. */
@Composable
internal fun SearchPane(
    ui: SearchUi,
    actions: SearchActions,
    grid: LazyGridState = rememberLazyGridState(),
    useText: (String) -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(4.dp)) {
            if (ui.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (ui.query.isBlank() && ui.similar == null) {
            Suggestions(ui.recent, actions, useText)
        } else {
            Results(ui, actions, grid, useText)
        }
    }
}

@Composable
private fun Suggestions(recent: List<String>, actions: SearchActions, useText: (String) -> Unit) {
    val pick = { q: String ->
        useText(q)
        actions.onSubmit(q)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (recent.isEmpty()) {
            item(key = "try-header") { Header("Try") }
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
                leadingContent = { Icon(Icons.Outlined.History, contentDescription = null) },
                trailingContent = {
                    IconButton(onClick = { actions.onRemoveRecent(q) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove $q from history")
                    }
                },
                modifier = Modifier.animateItem().clickable(onClickLabel = "Search") { pick(q) },
            )
        }
        item(key = "clear") {
            TextButton(onClick = actions.onClearRecent, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Clear history") }
        }
    }
}

@Composable
private fun Results(ui: SearchUi, actions: SearchActions, grid: LazyGridState, useText: (String) -> Unit) {
    val q = ui.query.trim()
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
            trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Stop searching by photo", Modifier.size(18.dp)) },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    val kinds = Kind.entries.filter { (ui.counts?.get(it) ?: 0L) > 0L }
    if (kinds.isNotEmpty()) {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "all") { KindChip("All", ui.chip == null) { actions.onChip(null) } }
            items(kinds, key = { it.name }) { k -> KindChip(Format.kindLabelPlural(k), ui.chip == k) { actions.onChip(k) } }
        }
    }
    val note = when {
        ui.similar != null && !ui.vectorLane -> "Search by photo works once setup finishes."
        !ui.setUp -> "Matching file names only until setup finishes."
        !ui.vectorLane -> "Matching exact words for now. Results by meaning will be back shortly."
        else -> null
    }
    note?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
    val hits = ui.hits
    // The words changed but haven't been searched yet: the hits still answer the old ones.
    val stale = ui.similar == null && ui.hitsFor != q
    val chip = ui.chip
    when {
        ui.failed -> Message {
            EmptyState("Search hit a snag", "Something went wrong. Try again.", Icons.Outlined.WarningAmber, "Try again" to actions.onRetry)
        }
        hits == null -> Unit
        hits.isEmpty() && stale -> Unit
        hits.isEmpty() && chip != null -> Message {
            EmptyState(
                "No matches in ${Format.kindLabelPlural(chip)}",
                if (ui.similar != null) "Nothing like this photo there." else "Nothing for “$q” there.",
                Icons.Outlined.SearchOff,
                "Search all files" to { actions.onChip(null) },
                art = R.drawable.illus_empty,
            )
        }
        hits.isEmpty() && ui.similar != null -> if (ui.vectorLane) {
            Message { EmptyState("Nothing like this yet", "Try another photo.", Icons.Outlined.ImageSearch, art = R.drawable.illus_empty) }
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
                Icons.Outlined.SearchOff,
                if (words.size >= 2) "Try fewer words" to { useText(words.dropLast(1).joinToString(" ")) } else null,
                art = R.drawable.illus_empty,
            )
        }
        else -> ResultGrid(hits, ui.hitsFor.ifEmpty { null }, ui.searching || stale, grid, actions.onOpenHit, actions.onFindSimilar)
    }
}

@Composable
private fun KindChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }
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
    query: String?,
    searching: Boolean,
    grid: LazyGridState,
    open: (SearchEngine.Hit) -> Unit,
    findSimilar: (SourceFile) -> Unit,
) {
    val (media, docs) = hits.partition(::inGrid)
    val mediaFirst = inGrid(hits.first())
    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        state = grid,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().alpha(if (searching) 0.6f else 1f),
    ) {
        item(key = "count", span = { GridItemSpan(maxLineSpan) }) {
            Text(
                if (hits.size == 1) "1 result" else "${Format.count(hits.size.toLong())} results",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (mediaFirst) {
            mediaSection(media, open, findSimilar)
            docSection(docs, query, open, findSimilar)
        } else {
            docSection(docs, query, open, findSimilar)
            mediaSection(media, open, findSimilar)
        }
    }
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

private fun LazyGridScope.mediaSection(hits: List<SearchEngine.Hit>, open: (SearchEngine.Hit) -> Unit, findSimilar: (SourceFile) -> Unit) {
    if (hits.isEmpty()) return
    sectionHeader("media", "Photos and videos")
    items(hits, key = { it.fileId }) { hit ->
        val still = hit.file.kind != Kind.VIDEO
        ThumbCell(
            hit.file,
            onClick = { open(hit) },
            modifier = Modifier.animateItem(),
            locator = hit.locator,
            onLongClick = if (still) ({ findSimilar(hit.file) }) else null,
            onLongClickLabel = if (still) "Find similar" else null,
        )
    }
}

private fun LazyGridScope.docSection(
    hits: List<SearchEngine.Hit>,
    query: String?,
    open: (SearchEngine.Hit) -> Unit,
    findSimilar: (SourceFile) -> Unit,
) {
    if (hits.isEmpty()) return
    sectionHeader("docs", "Documents and more")
    items(hits, key = { it.fileId }, span = { GridItemSpan(maxLineSpan) }) { hit ->
        val picture = hit.file.kind == Kind.SCREENSHOT
        FileRow(
            hit.file,
            modifier = Modifier.animateItem().bleed(16.dp),
            snippet = hit.snippet,
            locator = hit.locator,
            query = query,
            onLongClick = if (picture) ({ findSimilar(hit.file) }) else null,
            onLongClickLabel = if (picture) "Find similar" else null,
            onClick = { open(hit) },
        )
    }
}

/** Widens a full-line grid item past the grid's side padding, so a row's own padding lines it up with the cells. */
private fun Modifier.bleed(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = (by * 2).roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}
