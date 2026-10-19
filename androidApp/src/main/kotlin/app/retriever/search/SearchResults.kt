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

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.retriever.R
import app.retriever.access.Photos
import app.retriever.core.Kind
import app.retriever.core.ReadExtent
import app.retriever.core.SearchEngine
import app.retriever.core.SearchPhase
import app.retriever.core.SearchTiming
import app.retriever.core.SourceFile
import app.retriever.core.isStale
import app.retriever.core.motion.MascotMood
import app.retriever.core.showsEmpty
import app.retriever.home.HomeUi
import app.retriever.motion.Mascot
import app.retriever.ui.Format
import app.retriever.ui.components.EmptyState
import app.retriever.ui.components.FileRow
import app.retriever.ui.components.PressMenu
import app.retriever.ui.components.ThumbCell
import app.retriever.viewer.Opener
import kotlinx.coroutines.delay

@Composable
internal fun Results(
    ui: SearchUi,
    home: HomeUi,
    actions: SearchActions,
    star: (SourceFile, Boolean) -> Unit,
    grid: LazyGridState,
    useText: (String) -> Unit,
    side: Dp,
) {
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
    val question = ui.question
    val phase = ui.phase
    val results = phase as? SearchPhase.Results
    val shown = results?.result
    // The answer on screen was to other words or filters: newer ones are being searched.
    val stale = phase.isStale(question)
    // What was understood is of the words searched; while newer ones wait, it would be of the old ones.
    val parsed = shown?.parsed?.takeIf { ui.similar == null && !stale && it.understood.isNotEmpty() }
    // Kinds the words asked for show as picked chips too, so the row never says "All" over filtered results.
    val wordKinds = parsed?.kinds.orEmpty()
    val kinds = Kind.entries.filter { (home.counts?.get(it) ?: 0L) > 0L }
    val canStar = home.starred > 0 || ui.onlyStarred
    val scope = ui.scope
    // On the first run a few dozen files have no use for kind chips.
    if (scope == null && (kinds.isNotEmpty() || canStar)) {
        LazyRow(contentPadding = PaddingValues(horizontal = edge), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "all") {
                KindChip("All", ui.chip == null && !ui.onlyStarred && wordKinds.isEmpty()) {
                    actions.onChip(null)
                    actions.onStarredOnly(false)
                    if (wordKinds.isNotEmpty()) actions.onWordsAsTyped()
                }
            }
            if (canStar) item(key = "starred") { KindChip("Starred", ui.onlyStarred) { actions.onStarredOnly(!ui.onlyStarred) } }
            items(kinds, key = { it.name }) { k ->
                KindChip(Format.kindLabelPlural(k), ui.chip == k || k in wordKinds) {
                    actions.onChip(
                        if (ui.chip ==
                            k
                        ) {
                            null
                        } else {
                            k
                        },
                    )
                }
            }
        }
    }
    if (scope != null) {
        Column(Modifier.padding(horizontal = edge, vertical = 8.dp)) {
            Text(Format.closestIn(scope.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            if (ui.unread >
                0
            ) {
                Text(Format.stillReading(ui.unread), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
        if (shown.relaxed && shown.hits.isNotEmpty()) {
            Text(
                it.relaxedNote(Format.scope(ui.chip, ui.onlyStarred)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = edge, vertical = 8.dp),
            )
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
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = edge, vertical = 8.dp),
        )
    }
    val hits = shown?.hits.orEmpty()
    val chip = ui.chip
    val mood = if (ui.quietNoMatch) MascotMood.NO_MATCH_QUIET else MascotMood.NO_MATCH
    val searchAll = {
        actions.onChip(null)
        actions.onStarredOnly(false)
    }
    // "Nothing found" only once the whole search for these very words and filters has answered.
    val empty = phase.showsEmpty(question)
    when {
        phase is SearchPhase.Failed && !stale -> Message {
            EmptyState(
                "Search hit a snag",
                "Something went wrong. Try again.",
                R.drawable.ic_warning_amber,
                action = "Try again" to actions.onRetry,
                mood = MascotMood.TROUBLE,
            )
        }

        empty && (chip != null || ui.onlyStarred) -> Message {
            val where = Format.scope(chip, ui.onlyStarred)
            EmptyState(
                "No matches in your $where",
                if (ui.similar != null) "Nothing like this photo there." else "Nothing for “$q” there.",
                R.drawable.ic_search_off,
                action = "Search all files" to searchAll,
                mood = mood,
            )
        }

        empty && ui.similar != null -> if (ui.vectorLane) {
            Message { EmptyState("Nothing like this yet", "Try another photo.", R.drawable.ic_image_search, mood = mood) }
        }

        empty -> Message {
            val words = q.split(Regex("\\s+"))
            val unread = Format.count(ui.unread.toLong())
            val read = "${Format.count(home.searchable)} of ${Format.count(home.counts?.values?.sum() ?: 0L)}"
            val message = when {
                scope != null && ui.unread > 0 -> "Nothing yet. Retriever is still reading $unread more files, so try again in a moment, or try other words."
                scope != null -> "Nothing for “$q” in these files. Try other words."
                home.waiting > 0 -> "Retriever has read $read files so far. Try again later, or try other words."
                else -> "Try other or fewer words."
            }
            EmptyState(
                "No matches for “$q”",
                message,
                R.drawable.ic_search_off,
                action = if (words.size >= 2) "Try fewer words" to { useText(words.dropLast(1).joinToString(" ")) } else null,
                mood = mood,
            )
        }

        results != null && hits.isNotEmpty() -> {
            // Read when a menu opens, so starring one file recomposes that menu and not the grid.
            val starred by rememberUpdatedState(ui.starredUris)
            // Read at the tap: the viewer swipes through the photos among these hits.
            val hitsNow by rememberUpdatedState(hits)
            val textOnly = ui.textOnly
            val act = remember(actions, textOnly, star) {
                HitActions({ hit ->
                    Opener.queue(hitsNow.map { it.file })
                    actions.onOpenHit(hit)
                }, actions.onFindSimilar.takeUnless { textOnly }, { starred }, star)
            }
            // An answer to earlier words or filters dims while the new one is searched; a names answer is current, so it doesn't.
            ResultGrid(hits, results.result.strongCount, ui.extents, results.question.text.ifEmpty { null }, stale, grid, edge, act)
        }

        // Nothing to show yet: the dog sniffs once the wait is noticeable, below the chips and notes, never over hits.
        else -> if (heldFor(SearchTiming.SEARCHING_MASCOT_MS, true, question)) {
            Message { Mascot(MascotMood.SEARCHING, Modifier.width(160.dp).testTag(SEARCHING_MASCOT_TAG)) }
        }
    }
}

internal const val SEARCHING_MASCOT_TAG = "searching-mascot"

/** True once [on] has held for [ms] (restarting when [key] changes), so a quick search shows no flicker. */
@Composable
internal fun heldFor(ms: Long, on: Boolean, key: Any? = null): Boolean {
    val held by produceState(false, on, key) {
        value = false
        if (on) {
            delay(ms)
            value = true
        }
    }
    return on && held
}

// Material's standard fast-effects and default-spatial springs (its MotionScheme is internal in material3 1.4).
private val fastEffects = spring<Float>(dampingRatio = 1f, stiffness = 3800f)
private val defaultSpatial = spring<IntOffset>(dampingRatio = 0.9f, stiffness = 700f)

/** What a result can do: open, find similar (null when the phone cannot), and be starred. */
private class HitActions(
    val open: (SearchEngine.Hit) -> Unit,
    val findSimilar: ((SourceFile) -> Unit)?,
    val starred: () -> Set<String>,
    val star: (SourceFile, Boolean) -> Unit,
)

/** [content] is a result; a long press on it opens a menu to star it, and to find similar when [similar]. */
@Composable
private fun HitMenu(
    file: SourceFile,
    act: HitActions,
    similar: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (onLongClick: () -> Unit) -> Unit,
) {
    PressMenu(content, modifier) { dismiss ->
        app.retriever.files.FileActionItems(file, dismiss)
        val on = file.uri in act.starred()
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
    stale: Boolean,
    grid: LazyGridState,
    edge: Dp,
    act: HitActions,
) {
    val visibleCount = strongCount.coerceIn(if (hits.isEmpty()) 0 else 1, hits.size)
    val strong = hits.take(visibleCount)
    val weak = hits.drop(visibleCount)
    var expanded by remember(query, hits) { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (stale) 0.6f else 1f, fastEffects)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        state = grid,
        contentPadding = PaddingValues(start = edge, end = edge, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha },
    ) {
        sections(strong, extents, query, titled = true, act)
        if (weak.isNotEmpty()) {
            item(key = "weak", span = { GridItemSpan(maxLineSpan) }) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.semantics {
                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                    },
                ) { Text("Less likely matches (${weak.size})") }
            }
            if (expanded) sections(weak, extents, query, titled = false, act)
        }
    }
}

/**
 * Pictures and documents of [hits] in rank order as far as a grid allows: the documents ranked above the best picture,
 * then the pictures, then the other documents. Neither one strong document nor one strong photo buries the rest of
 * the other kind. [titled] gives each its header.
 */
private fun LazyGridScope.sections(hits: List<SearchEngine.Hit>, extents: Map<Long, ReadExtent>, query: String?, titled: Boolean, act: HitActions) {
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
private fun inGrid(hit: SearchEngine.Hit): Boolean = hit.file.kind in visualKinds && !(hit.file.kind == Kind.SCREENSHOT && !hit.snippet.isNullOrBlank())

private fun LazyGridScope.sectionHeader(key: String, title: String) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.animateItem(
                fadeInSpec = fastEffects,
                placementSpec = defaultSpatial,
                fadeOutSpec = fastEffects,
            ).padding(top = 12.dp).semantics {
                heading()
            },
        )
    }
}

private fun LazyGridScope.mediaSection(hits: List<SearchEngine.Hit>, extents: Map<Long, ReadExtent>, titled: Boolean, act: HitActions) {
    if (hits.isEmpty()) return
    if (titled) sectionHeader("media", "Photos and videos")
    items(hits, key = { it.fileId }, contentType = { "thumb" }) { hit ->
        HitMenu(
            hit.file,
            act,
            similar = hit.file.kind != Kind.VIDEO && act.findSimilar != null,
            Modifier.animateItem(fadeInSpec = fastEffects, placementSpec = defaultSpatial, fadeOutSpec = fastEffects),
        ) { menu ->
            Column {
                ThumbCell(
                    hit.file,
                    onClick = { act.open(hit) },
                    locator = hit.locator,
                    readNote = Format.readNote(extents[hit.fileId]),
                    onLongClick = menu,
                    onLongClickLabel = "More options",
                )
                hit.matchReason?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
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
    items(hits, key = { it.fileId }, span = { GridItemSpan(maxLineSpan) }, contentType = { "row" }) { hit ->
        HitMenu(
            hit.file,
            act,
            similar = hit.file.kind == Kind.SCREENSHOT && act.findSimilar != null,
            Modifier.animateItem(fadeInSpec = fastEffects, placementSpec = defaultSpatial, fadeOutSpec = fastEffects).bleed(16.dp),
        ) { menu ->
            FileRow(
                hit.file,
                snippet = hit.snippet,
                locator = hit.locator,
                // Bold the words only where the file was found by its name.
                query = query.takeIf { hit.byName },
                readNote = listOfNotNull(hit.matchReason, Format.readNote(extents[hit.fileId])).joinToString(" · ").ifEmpty { null },
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
