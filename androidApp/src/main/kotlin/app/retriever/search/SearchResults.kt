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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.retriever.R
import app.retriever.access.Access
import app.retriever.access.openAllFilesSettings
import app.retriever.core.DeviceWords
import app.retriever.core.Kind
import app.retriever.core.ReadExtent
import app.retriever.core.SearchEngine
import app.retriever.core.SearchPhase
import app.retriever.core.SearchTiming
import app.retriever.core.SourceFile
import app.retriever.core.Words
import app.retriever.core.isStale
import app.retriever.core.motion.MascotMood
import app.retriever.core.showsEmpty
import app.retriever.home.HomeUi
import app.retriever.motion.Mascot
import app.retriever.nav.SettingsRoute
import app.retriever.ui.Format
import app.retriever.ui.components.CappedCells
import app.retriever.ui.components.EmptyState
import app.retriever.ui.components.FileRow
import app.retriever.ui.components.PhotoMorph
import app.retriever.ui.components.ThumbCell
import app.retriever.ui.components.Thumbnails
import app.retriever.ui.components.readingWidth
import app.retriever.viewer.Opener
import kotlinx.coroutines.delay

@Composable
internal fun Results(ui: SearchUi, home: HomeUi, actions: SearchActions, star: (SourceFile, Boolean) -> Unit, grid: LazyGridState, useText: (String) -> Unit) {
    val q = ui.query.trim()
    // The headers share the results' column; a chip or button keeps its own width at the column's start.
    val edge = Modifier.readingWidth().padding(horizontal = 16.dp)
    val start = edge.wrapContentWidth(Alignment.Start)
    ui.similar?.let { s ->
        InputChip(
            selected = true,
            onClick = actions.onClearSimilar,
            label = { Text(s.label?.let { Words.strings.likeFile(it) } ?: Words.strings.likeYourPhoto, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            avatar = s.thumb?.let { thumb ->
                {
                    Image(
                        thumb,
                        contentDescription = Words.strings.photoYoureSearchingWith,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(InputChipDefaults.AvatarSize).clip(CircleShape),
                    )
                }
            },
            trailingIcon = { Icon(painterResource(R.drawable.ic_close), contentDescription = Words.strings.stopSearchingByPhoto, Modifier.size(18.dp)) },
            modifier = start,
        )
    }
    val question = ui.question
    val phase = ui.phase
    val results = phase as? SearchPhase.Results
    val shown = results?.result
    // The answer on screen was to other words or filters: newer ones are being searched. It dims only once the wait is
    // noticeable, like the bar, so typing doesn't pulse it at every key.
    val stale = phase.isStale(question)
    val dim = heldFor(SearchTiming.SEARCHING_BAR_MS, stale && ui.searching, question)
    // What was understood is of the words searched; while newer ones wait, it would be of the old ones.
    val parsed = shown?.parsed?.takeIf { ui.similar == null && !stale && it.understood.isNotEmpty() }
    // Kinds the words asked for show as picked chips too, so the row never says "All" over filtered results.
    val wordKinds = parsed?.kinds.orEmpty()
    val kinds = Kind.entries.filter { (home.counts?.get(it) ?: 0L) > 0L }
    val canStar = home.starred > 0 || ui.onlyStarred
    val scope = ui.scope
    // On the first run a few dozen files have no use for kind chips.
    if (scope == null && (kinds.isNotEmpty() || canStar)) KindChips(kinds, ui.chip, ui.onlyStarred, wordKinds, canStar, actions)
    if (scope != null) {
        val finished = ui.unread == 0 && ui.samplePlanned > 0
        Text(
            Words.demoSearchScope(ui.sampleDone.toLong(), finished, DeviceWords.ANDROID),
            style = MaterialTheme.typography.bodyMedium,
            modifier = edge.padding(vertical = 8.dp),
        )
    }
    parsed?.let {
        InputChip(
            selected = true,
            onClick = actions.onWordsAsTyped,
            label = { Text(Words.strings.understoodChip(it.understood), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { Icon(painterResource(R.drawable.ic_close), contentDescription = Words.strings.searchWordsAsTyped, Modifier.size(18.dp)) },
            modifier = start,
        )
        if (shown.relaxed && shown.hits.isNotEmpty()) {
            Text(
                it.relaxedNote(Format.scope(ui.chip, ui.onlyStarred)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = edge.padding(vertical = 8.dp),
            )
        }
    }
    val hits = shown?.hits.orEmpty()
    // Name matches are up; the words inside are still being searched. Said only once that takes a while, or the line
    // would push the results down and back at every key.
    val contentsWait = heldFor(SearchTiming.SEARCHING_BAR_MS, ui.searching && hits.isNotEmpty() && !stale, question)
    val note = when {
        contentsWait -> Words.SEARCHING_CONTENTS

        ui.similar != null && !ui.vectorLane -> Words.strings.photoSearchAfterSetup

        // Words only is said by no label: the download hint under few results says what more there is.
        ui.wordsOnly -> null

        !ui.vectorLane -> Words.strings.matchingExactWordsForNow

        else -> null
    }
    note?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = edge.padding(vertical = 8.dp),
        )
    }
    // Few results without the model: one line to the upgrade, in Settings' Storage and downloads.
    Words.upgradeHint(hits.size, ui.upgrade).takeIf { results != null && !ui.searching && ui.similar == null }?.let {
        TextButton(
            onClick = { actions.onNavigate(SettingsRoute) },
            modifier = Modifier.readingWidth().padding(horizontal = 4.dp).wrapContentWidth(Alignment.Start),
        ) { Text(it) }
    }
    val chip = ui.chip
    val mood = if (ui.quietNoMatch) MascotMood.NO_MATCH_QUIET else MascotMood.NO_MATCH
    val searchAll = {
        actions.onChip(null)
        actions.onStarredOnly(false)
    }
    // "Nothing found" only once the whole search for these very words and filters has answered. It then stays, dimmed
    // like old hits, while newer words are searched, so typing never blinks it away and back.
    val empty = phase.showsEmpty(question) || (stale && results?.complete == true && hits.isEmpty())
    // A whole, current answer with few results, from partial access: say what was left out, and the way to the rest.
    val wider = home.access.takeIf {
        showsWiderSearch(it, hits.size) && results?.complete == true && !ui.searching && !stale && ui.similar == null && scope == null
    }
    val asked = results?.question?.text ?: q
    when {
        phase is SearchPhase.Failed && !stale -> Message {
            EmptyState(
                Words.SEARCH_FAILED,
                Words.strings.somethingWentWrongTryAgain,
                R.drawable.ic_warning_amber,
                action = Words.TRY_AGAIN to actions.onRetry,
                mood = MascotMood.TROUBLE,
            )
        }

        empty && (chip != null || ui.onlyStarred) -> Message(dim) {
            val where = Format.scope(chip, ui.onlyStarred)
            EmptyState(
                Words.strings.noMatchesIn(where.orEmpty()),
                if (ui.similar != null) Words.strings.nothingLikePhotoThere else Words.strings.nothingForThere(asked),
                R.drawable.ic_search_off,
                action = Words.strings.searchAllFiles to searchAll,
                mood = mood,
            )
        }

        empty && ui.similar != null -> if (ui.vectorLane) {
            Message(dim) { EmptyState(Words.strings.nothingLikeThisYet, Words.strings.tryAnotherPhoto, R.drawable.ic_image_search, mood = mood) }
        }

        empty -> Message(dim) {
            val words = asked.split(Regex("\\s+"))
            val waiting = if (scope != null) ui.unread > 0 else home.progress.scheduled > 0
            val message = Words.strings.noMatchesMessage(!ui.wordsOnly, waiting, ui.wordsOnly)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState(
                    Words.NO_MATCHES,
                    message,
                    R.drawable.ic_search_off,
                    action = if (words.size >= 2) Words.strings.tryFewerWords to { useText(words.dropLast(1).joinToString(" ")) } else null,
                    mood = mood,
                )
                wider?.let { WiderSearchHint(it, Modifier.readingWidth().padding(16.dp)) }
            }
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
                }, actions.onFindSimilar.takeUnless { textOnly }, { starred }, star, actions.morph)
            }
            // An answer to earlier words or filters dims while the new one is searched; a names answer is current, so it doesn't.
            ResultGrid(hits, results.result.strongCount, ui.extents, results.question.text.ifEmpty { null }, dim, grid, act, wider)
        }

        // Nothing to show yet: the dog sniffs once the wait is noticeable, below the chips and notes, never over hits.
        else -> if (heldFor(SearchTiming.SEARCHING_MASCOT_MS, ui.searching, question)) {
            Message { Mascot(MascotMood.SEARCHING, Modifier.width(160.dp).testTag(SEARCHING_MASCOT_TAG)) }
        }
    }
}

/** Up to this many results, a search over partial access says what it left out ([WiderSearchHint]). */
internal const val FEW_FOR_WIDER_SEARCH = 3

internal fun showsWiderSearch(access: Access, results: Int): Boolean = access.partial && results <= FEW_FOR_WIDER_SEARCH

/** What a search over partial access covered, and "Search your whole phone", which opens Android's all-files switch. */
@Composable
private fun WiderSearchHint(access: Access, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Surface(
        onClick = { openAllFilesSettings(context) },
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(access.granted(Words.strings::searchedOnly), style = MaterialTheme.typography.bodyMedium)
                Text(
                    Words.strings.wholeDeviceTitle(DeviceWords.ANDROID),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

internal const val SEARCHING_MASCOT_TAG = "searching-mascot"

/** The results grid's photo-morph origin: a hit's cell and its viewer share it. */
internal const val SEARCH_ORIGIN = "search"

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

/** The results grid's narrowest column and its gaps; [SearchPane] works the column's width out from them. */
internal val RESULT_CELL_MIN = 110.dp
internal val RESULT_CELL_GAP = 8.dp

// Material's standard fast-effects spring (its MotionScheme is internal in material3 1.4). Results only fade in: they snap
// to their places and leave at once, since sliding and fading together drew rows over each other at every key.
private val fastEffects = spring<Float>(dampingRatio = 1f, stiffness = 3800f)

/** What a result can do: open, find similar (null when the phone cannot), and be starred. */
private class HitActions(
    val open: (SearchEngine.Hit) -> Unit,
    val findSimilar: ((SourceFile) -> Unit)?,
    val starred: () -> Set<String>,
    val star: (SourceFile, Boolean) -> Unit,
    val morph: PhotoMorph?,
)

/**
 * [content] is a result; a long press on it opens a menu to star it, to find similar when [similar], and to open each of
 * its other copies.
 */
@Composable
private fun HitMenu(
    hit: SearchEngine.Hit,
    act: HitActions,
    similar: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (onLongClick: () -> Unit, actions: Modifier) -> Unit,
) {
    val file = hit.file
    // Derived per file, so starring one file recomposes that file's menu and no other.
    val starred by remember(file.uri) { derivedStateOf { file.uri in act.starred() } }
    app.retriever.files.FilePressMenu(
        file,
        modifier,
        starred = starred,
        onStar = { act.star(file, it) },
        similar = act.findSimilar?.takeIf { similar }?.let { find -> { find(file) } },
        copies = hit.copies.map { it.file },
        openCopy = { copy -> hit.copies.firstOrNull { it.file == copy }?.let(act.open) },
        content = content,
    )
}

/** Given the filters, not the whole [SearchUi]: a keystroke brings several answers and leaves every chip as it was. */
@Composable
private fun KindChips(kinds: List<Kind>, chip: Kind?, onlyStarred: Boolean, wordKinds: Set<Kind>, canStar: Boolean, actions: SearchActions) {
    LazyRow(Modifier.readingWidth(), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "all") {
            KindChip(Words.strings.filterAll, chip == null && !onlyStarred && wordKinds.isEmpty()) {
                actions.onChip(null)
                actions.onStarredOnly(false)
                if (wordKinds.isNotEmpty()) actions.onWordsAsTyped()
            }
        }
        if (canStar) item(key = "starred") { KindChip(Words.strings.filterStarred, onlyStarred) { actions.onStarredOnly(!onlyStarred) } }
        items(kinds, key = { it.name }) { k ->
            KindChip(Words.kindLabelPlural(k), chip == k || k in wordKinds) { actions.onChip(if (chip == k) null else k) }
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
private fun Message(dim: Boolean = false, content: @Composable () -> Unit) {
    val alpha by animateFloatAsState(if (dim) 0.6f else 1f, fastEffects)
    Box(
        Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }.padding(vertical = 32.dp),
        contentAlignment = Alignment.TopCenter,
    ) { content() }
}

@Composable
private fun ResultGrid(
    hits: List<SearchEngine.Hit>,
    strongCount: Int,
    extents: Map<Long, ReadExtent>,
    query: String?,
    stale: Boolean,
    grid: LazyGridState,
    act: HitActions,
    /** Partial access under few results: [WiderSearchHint] after them. */
    wider: Access? = null,
) {
    val visibleCount = strongCount.coerceIn(if (hits.isEmpty()) 0 else 1, hits.size)
    val strong = hits.take(visibleCount)
    val weak = hits.drop(visibleCount)
    // Kept across opening a result and coming back, and while the same question's results refine; a new question closes it.
    var expanded by rememberSaveable(query) { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (stale) 0.6f else 1f, fastEffects)
    var request by remember { mutableIntStateOf(0) }
    val cells = remember { CappedCells(GridCells.Adaptive(RESULT_CELL_MIN), 840.dp - 16.dp * 2) { request = Thumbnails.bucket(it) } }
    val density = LocalDensity.current
    // Read by each cell as it composes, after the grid has worked out the columns.
    val cell = remember(density) { { with(density) { request.toDp() } } }
    LazyVerticalGrid(
        columns = cells,
        state = grid,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(RESULT_CELL_GAP, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(RESULT_CELL_GAP),
        modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha },
    ) {
        sections(strong, extents, query, titled = true, cell, act)
        if (weak.isNotEmpty()) {
            item(key = "weak", span = { GridItemSpan(maxLineSpan) }) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.semantics {
                        stateDescription = if (expanded) Words.strings.expanded else Words.strings.collapsed
                    },
                ) { Text(Words.strings.lessLikelyMatches(weak.size.toLong())) }
            }
            if (expanded) sections(weak, extents, query, titled = false, cell, act)
        }
        wider?.let { access ->
            item(key = "wider", span = { GridItemSpan(maxLineSpan) }) { WiderSearchHint(access, Modifier.padding(top = 8.dp)) }
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
    cell: () -> Dp,
    act: HitActions,
) {
    val (recordings, others) = hits.partition { it.file.kind == Kind.AUDIO }
    val (lead, media, rest) = sectionsOf(others)
    docSection(lead, extents, query, titled, act)
    mediaSection(media, extents, titled, cell, act)
    if (lead.isEmpty()) {
        docSection(rest, extents, query, titled, act)
    } else {
        docSection(rest, extents, query, titled, act, "docs-more", Words.strings.moreDocuments)
    }
    docSection(recordings, extents, query, titled, act, "recordings", Words.strings.recordings)
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
            modifier = Modifier.animateItem(fadeInSpec = fastEffects, placementSpec = null, fadeOutSpec = null).padding(top = 12.dp).semantics {
                heading()
            },
        )
    }
}

private fun LazyGridScope.mediaSection(hits: List<SearchEngine.Hit>, extents: Map<Long, ReadExtent>, titled: Boolean, cell: () -> Dp, act: HitActions) {
    if (hits.isEmpty()) return
    if (titled) sectionHeader("media", Words.PHOTOS_AND_VIDEOS)
    items(hits, key = { it.fileId }, contentType = { "thumb" }) { hit ->
        HitMenu(
            hit,
            act,
            similar = hit.file.kind != Kind.VIDEO && act.findSimilar != null,
            Modifier.animateItem(fadeInSpec = fastEffects, placementSpec = null, fadeOutSpec = null),
        ) { menu, actions ->
            Column {
                ThumbCell(
                    hit.file,
                    modifier = actions,
                    onClick = { act.open(hit) },
                    requestSize = cell(),
                    locator = hit.locator,
                    readNote = Words.extent(extents[hit.fileId]),
                    onLongClick = menu,
                    onLongClickLabel = Words.strings.moreOptions,
                    morph = act.morph,
                    origin = SEARCH_ORIGIN,
                )
                val line = Words.strings.reasonLine(listOfNotNull(hit.reason, hit.copiesNote))
                if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.labelSmall)
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
    header: String = Words.strings.documentsAndMore,
) {
    if (hits.isEmpty()) return
    if (titled) sectionHeader(headerKey, header)
    items(hits, key = { it.fileId }, span = { GridItemSpan(maxLineSpan) }, contentType = { "row" }) { hit ->
        HitMenu(
            hit,
            act,
            similar = hit.file.kind == Kind.SCREENSHOT && act.findSimilar != null,
            Modifier.animateItem(fadeInSpec = fastEffects, placementSpec = null, fadeOutSpec = null).bleed(16.dp),
        ) { menu, actions ->
            FileRow(
                hit.file,
                modifier = actions,
                snippet = hit.snippet,
                locator = hit.locator,
                // Bold the words only where the file was found by its name.
                query = query.takeIf { hit.byName },
                readNote = Words.strings.reasonLine(listOfNotNull(hit.reason, Words.extent(extents[hit.fileId]), hit.copiesNote)).ifEmpty { null },
                onLongClick = menu,
                onLongClickLabel = Words.strings.moreOptions,
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

/** Why the hit matched, as far as its provenance says: core's reason, or that its name matched. */
private val SearchEngine.Hit.reason: String? get() = matchReason ?: Words.NAME_MATCHES.takeIf { byName }

/** "2 copies" on a result that stands for the same file in several folders. */
private val SearchEngine.Hit.copiesNote: String? get() = copies.takeIf { it.isNotEmpty() }?.let { Words.copies(it.size + 1) }
