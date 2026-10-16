/*
 * Copyright 2026 Google LLC
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
// Tile adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/home/SquareDrawerItem.kt (Apache-2.0). Modified for Retriever: no icon brush or string resources, filled surfaceContainer, label plus count.

package app.retriever

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retriever.core.Kind
import java.text.NumberFormat

@Composable
fun SearchScreen(onNavigate: (Any) -> Unit) {
    val vm = appViewModel { SearchViewModel(it.retriever) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val state by vm.indexState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Scaffold { padding ->
        Column(Modifier.padding(padding)) {
            OutlinedTextField(
                value = ui.query,
                onValueChange = vm::setQuery,
                singleLine = true,
                placeholder = { Text("Search your files") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (ui.query.isNotEmpty()) {
                        IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
                    }
                },
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (ui.searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            if (ui.query.isBlank()) {
                val status = buildString {
                    append("${num(ui.searchable)} searchable · ${num(ui.waiting)} waiting")
                    if (ui.skipped > 0) append(" · ${num(ui.skipped)} skipped")
                }
                // Two fixed one-line rows, so the card (and the grid under it) keeps its height as
                // indexing starts and stops.
                val activity = when {
                    // The first sync runs before anything is counted.
                    state.running && state.pendingAtStart == 0L -> "Looking for your files…"
                    state.running -> "Reading files…"
                    else -> stopCopy(state.lastStop) ?: "Tap for indexing details"
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).clickable { onNavigate(StatusRoute) },
                        ) {
                            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Outlined.Storage, contentDescription = null)
                                Column {
                                    Text(status, style = MaterialTheme.typography.bodyLarge.tabular(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        activity,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                    items(Kind.entries) { kind ->
                        Tile(Format.kindLabelPlural(kind), ui.counts[kind]?.let(::num), kindIcon(kind)) {
                            onNavigate(BrowseRoute(kind = kind.name, title = Format.kindLabelPlural(kind)))
                        }
                    }
                    item { Tile("Folders", null, Icons.Outlined.FolderOpen) { onNavigate(FoldersRoute) } }
                    item { Tile("Large files", null, Icons.Outlined.Storage) { onNavigate(BrowseRoute(sort = "LARGEST", title = "Large files")) } }
                    item { Tile("Duplicates", null, Icons.Outlined.ContentCopy) { onNavigate(DuplicatesRoute) } }
                }
            } else {
                Results(ui, vm, onNavigate, context)
            }
        }
    }
}

@Composable
private fun Results(ui: SearchUi, vm: SearchViewModel, onNavigate: (Any) -> Unit, context: android.content.Context) {
    val hits = ui.hits
    when {
        ui.notReady -> Centered {
            EmptyState("Retriever isn't ready yet", "Finish setting up so it can read your files.", Icons.Outlined.WarningAmber, "Set up" to { onNavigate(SetupRoute) })
        }
        ui.failed -> Centered { EmptyState("Search hit a snag", "Try again in a moment.", Icons.Outlined.WarningAmber) }
        hits == null -> Unit
        hits.isEmpty() -> Centered {
            EmptyState(
                "Nothing matched yet",
                if (ui.waiting > 0) "Retriever is still reading ${num(ui.waiting)} files." else "No matches.",
                Icons.Outlined.SearchOff,
            )
        }
        else -> {
            val kinds = Kind.entries.filter { k -> hits.any { it.file.kind == k } }
            val shown = if (ui.chip == null) hits else ui.chipHits ?: hits.filter { it.file.kind == ui.chip }
            if (kinds.size > 1) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(kinds) { k ->
                        FilterChip(
                            selected = ui.chip == k,
                            onClick = { vm.setChip(if (ui.chip == k) null else k) },
                            label = { Text(Format.kindLabelPlural(k)) },
                        )
                    }
                }
            }
            ui.note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            LazyColumn {
                items(shown, key = { it.chunkId }) { hit ->
                    FileRow(
                        file = hit.file,
                        subtitle = Format.kindLabel(hit.file.kind) + if (hit.locator.isNotBlank()) " · ${hit.locator}" else "",
                        snippet = hit.snippet,
                        onClick = { Opener.open(context, hit.file, hit.locator, onNavigate) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun Tile(label: String, count: String?, icon: ImageVector, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick),
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
                    if (count != null) {
                        Text(count, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun num(n: Long): String = NumberFormat.getIntegerInstance().format(n)
