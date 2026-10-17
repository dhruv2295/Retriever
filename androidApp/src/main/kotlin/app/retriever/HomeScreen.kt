package app.retriever

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.shape.CircleShape
import androidx.annotation.DrawableRes
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.retriever.core.IndexState
import app.retriever.core.Kind
import app.retriever.core.SourceFile

// Home: what to look for, whether search is complete, what is new, where everything is. Colour tokens only, so
// wallpaper colours and the warm palette both work. Spec: the Home design in the wave 5 notes.

private val FILE_KINDS = listOf(Kind.SCREENSHOT, Kind.PDF, Kind.DOC, Kind.PHOTO, Kind.VIDEO, Kind.AUDIO, Kind.OTHER)
private val transparent @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)
private val FullLine = StaggeredGridItemSpan.FullLine

/** [fill] puts a past search in the field and opens the bar; true also runs it. */
@Composable
internal fun HomeBody(
    ui: SearchUi,
    index: IndexState,
    actions: SearchActions,
    state: LazyStaggeredGridState,
    padding: PaddingValues,
    side: Dp,
    fill: (String, Boolean) -> Unit,
) {
    val counts = ui.counts
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(380.dp),
        state = state,
        contentPadding = contentPaddingFor(padding, side = side),
        verticalItemSpacing = 24.dp,
        modifier = Modifier.fillMaxSize(),
    ) {
        // Not loaded yet: show nothing rather than flash the first-run message over a full index.
        if (counts == null) return@LazyVerticalStaggeredGrid
        val total = counts.values.sum()
        if (total == 0L) {
            // Still looking while setup runs; "nothing" only once a finished look found nothing.
            val looking = index.running || !ui.setUp
            if (ui.hasAccess) item(key = "empty", span = FullLine) { EmptyHome(looking) } else item(key = "status", span = FullLine) { Status(ui, index, total, actions) }
            return@LazyVerticalStaggeredGrid
        }
        item(key = "status", span = FullLine) { Status(ui, index, total, actions) }
        val recent = ui.recent.take(3)
        if (recent.isNotEmpty()) {
            item(key = "recent") {
                Column {
                    SectionHeader("Recent searches", "Clear", "Clear recent searches", actions.onClearRecent)
                    for (q in recent) {
                        ListItem(
                            headlineContent = { Text(q, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Icon(painterResource(R.drawable.ic_history), contentDescription = null) },
                            trailingContent = {
                                IconButton(onClick = { fill(q, false) }) { Icon(painterResource(R.drawable.ic_north_west), contentDescription = "Edit “$q”") }
                            },
                            colors = transparent,
                            modifier = Modifier.clickable(onClickLabel = "Search again") { fill(q, true) },
                        )
                    }
                }
            }
        } else if (ui.suggestions.isNotEmpty()) {
            item(key = "try") {
                Column {
                    SectionHeader("Try searching")
                    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (q in ui.suggestions) SuggestionChip(onClick = { fill(q, true) }, label = { Text(q) })
                    }
                }
            }
        }
        val home = ui.home
        home?.newDocs?.takeIf { it.isNotEmpty() }?.let { docs ->
            item(key = "docs") {
                Column {
                    val kind = docs.first().kind
                    SectionHeader("New documents", "See all", "See all ${Format.kindLabelPlural(kind)}") {
                        actions.onNavigate(BrowseRoute(kind = kind.name, title = Format.kindLabelPlural(kind)))
                    }
                    for (f in docs) FileRow(f, subtitle = Format.fileLine(f), onClick = { actions.onOpenFile(f) })
                }
            }
        }
        home?.newShots?.takeIf { it.isNotEmpty() }?.let { shots ->
            item(key = "shots") {
                Column {
                    SectionHeader("New screenshots", "See all", "See all screenshots") {
                        actions.onNavigate(BrowseRoute(kind = Kind.SCREENSHOT.name, title = Format.kindLabelPlural(Kind.SCREENSHOT)))
                    }
                    // The last cell is cut by the edge, so the strip reads as scrollable.
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(shots, key = { it.uri }) { f -> ShotTile(f) { actions.onOpenFile(f) } }
                    }
                }
            }
        }
        item(key = "files") { YourFiles(ui, counts, actions.onNavigate) }
        item(key = "tidy") { TidyUp(ui, actions.onNavigate) }
    }
}

/** A phone-shaped (9:16) screenshot tile; ThumbCell is always square. */
@Composable
private fun ShotTile(file: SourceFile, onClick: () -> Unit) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { 192.dp.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(null, file.uri, file.modifiedAt) { value = Thumbnails.load(context, file, px) }
    Box(
        Modifier
            .size(96.dp, 171.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .semantics(mergeDescendants = true) { contentDescription = "Screenshot, ${Format.date(file.modifiedAt)}" }
            .clickable(onClickLabel = "Open", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val shown = bitmap
        if (shown != null) Image(shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(painterResource(kindIcon(file.kind)), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionHeader(title: String, action: String? = null, actionLabel: String = "", onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = if (action == null) 16.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(bottom = if (action == null) 8.dp else 0.dp).semantics { heading() },
        )
        if (action != null) TextButton(onClick = onAction, modifier = Modifier.semantics { contentDescription = actionLabel }) { Text(action) }
    }
}

// --- Status ---

@Composable
private fun Status(ui: SearchUi, index: IndexState, total: Long, actions: SearchActions) {
    val colors = MaterialTheme.colorScheme
    val of = "${Format.count(ui.searchable)} of ${Format.count(total)}"
    val go = actions.onNavigate
    // Access first: setUp needs access too, so without it "Finishing setup" would show instead of the way back.
    when {
        !ui.hasAccess -> StatusCard(
            colors.tertiaryContainer,
            colors.onTertiaryContainer,
            "Retriever can't see your files",
            body = "Search still works on the ${Format.count(ui.searchable)} files already read. New files won't show up until you allow access.",
            action = "Allow access" to actions.onAllowAccess,
        )
        !ui.setUp -> StatusCard(colors.surfaceContainer, colors.onSurface, "Finishing setup. For now Retriever matches file names.", onClick = { go(SetupRoute) }, clickLabel = "Finish setup")
        index.running && index.pendingAtStart > 0L -> StatusCard(
            colors.surfaceContainer,
            colors.onSurface,
            "Reading your files",
            count = of,
            body = index.etaMinutes?.let { Format.eta(it).replaceFirstChar { c -> c.uppercase() } + "." + if (it >= 60 && !ui.charging) " Faster while charging." else "" },
            progress = if (total > 0) ui.searchable.toFloat() / total else 0f,
            onClick = { go(StatusRoute) },
        )
        index.running -> QuietRow("Looking for new files…", null, null, false, go)
        ui.paused -> StatusCard(
            colors.secondaryContainer,
            colors.onSecondaryContainer,
            "Paused",
            count = "$of searchable",
            body = "Search works on everything read so far.",
            icon = R.drawable.ic_pause,
            onClick = { go(StatusRoute) },
            action = "Resume" to actions.onResume,
        )
        ui.waiting > 0 -> StatusCard(
            colors.secondaryContainer,
            colors.onSecondaryContainer,
            waitingCopy(index.lastStop) ?: "Waiting",
            count = "$of searchable",
            body = "Search works on everything read so far.",
            icon = R.drawable.ic_schedule,
            onClick = { go(StatusRoute) },
        )
        else -> QuietRow(
            "All caught up",
            "${Format.count(ui.searchable)} files searchable",
            ui.home?.nameOnly?.takeIf { it > 0 }?.let { "${Format.count(it)} of them by name only" },
            true,
            go,
        )
    }
}

/**
 * "[title] · [count]" on one line where it fits. Only [title] is a live region, so TalkBack says when the state
 * changes but not every tick of the count. It is its own node: merged into the clickable row, the row would become
 * the live region and every count change would be announced again.
 */
@Composable
private fun TitleWithCount(title: String, count: String?, style: TextStyle) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = style, modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite })
        if (count != null) Text("· $count", style = style.tabular())
    }
}

@Composable
private fun StatusCard(
    container: Color,
    content: Color,
    title: String,
    count: String? = null,
    body: String? = null,
    @DrawableRes icon: Int? = null,
    progress: Float? = null,
    onClick: (() -> Unit)? = null,
    clickLabel: String = "Show reading details",
    action: Pair<String, () -> Unit>? = null,
) {
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column {
            Row(
                Modifier
                    .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = clickLabel, onClick = onClick) else Modifier)
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (icon != null) Icon(painterResource(icon), contentDescription = null)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TitleWithCount(title, count, MaterialTheme.typography.bodyLarge)
                    if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium)
                }
                if (onClick != null) Icon(painterResource(R.drawable.ic_keyboard_arrow_right), contentDescription = null)
            }
            // Filled, not tonal: a tonal button is the same colour as these cards and would vanish.
            if (action != null) Button(onClick = action.second, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) { Text(action.first) }
        }
    }
}

/** Nothing to act on: one line, no card. */
@Composable
private fun QuietRow(title: String, count: String?, second: String?, done: Boolean, go: (Route) -> Unit) {
    ListItem(
        leadingContent = if (!done) null else ({
            Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
        }),
        headlineContent = { TitleWithCount(title, count, LocalTextStyle.current) },
        supportingContent = second?.let { { Text(it) } },
        trailingContent = { Icon(painterResource(R.drawable.ic_keyboard_arrow_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = transparent,
        modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Show reading details") { go(StatusRoute) },
    )
}

@Composable
private fun EmptyHome(looking: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(painterResource(if (looking) R.drawable.illus_reading else R.drawable.illus_empty), contentDescription = null, modifier = Modifier.size(160.dp))
        Text(if (looking) "Looking for your files" else "Nothing to search yet", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            if (looking) "This takes a minute. You can search by name as soon as they turn up." else "Save a PDF, take a screenshot or a photo, and it'll show up here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// --- Your files, Tidy up ---

@Composable
private fun YourFiles(ui: SearchUi, counts: Map<Kind, Long>, go: (Route) -> Unit) {
    Column {
        SectionHeader("Your files")
        Card {
            if (ui.starred > 0) {
                CountRow(R.drawable.ic_star_border, "Starred", ui.starred.toLong(), null) { go(BrowseRoute(title = "Starred", starredOnly = true)) }
            }
            for (kind in FILE_KINDS) {
                val n = counts[kind] ?: 0L
                if (n == 0L) continue
                val pending = ui.pending[kind] ?: 0L
                val line = when {
                    kind == Kind.OTHER || kind !in ui.inside -> "Found by name"
                    ui.waiting == 0L -> null
                    pending == 0L -> "Ready"
                    pending < n -> "${Format.count(pending)} to go"
                    else -> "Waiting"
                }
                CountRow(kindIcon(kind), Format.kindLabelPlural(kind), n, line) { go(BrowseRoute(kind = kind.name, title = Format.kindLabelPlural(kind))) }
            }
            val folders = ui.home?.folders ?: 0L
            if (folders > 0) CountRow(R.drawable.ic_folder_open, "Folders", folders, null, ofFiles = false) { go(FoldersRoute) }
        }
    }
}

@Composable
private fun TidyUp(ui: SearchUi, go: (Route) -> Unit) {
    Column {
        SectionHeader("Tidy up")
        Card {
            val home = ui.home
            if (home != null && home.largeCount > 0) {
                TidyRow(R.drawable.ic_storage, "${Format.count(home.largeCount)} files over 100 MB · ${Format.bytes(home.largeBytes)}") {
                    go(BrowseRoute(sort = "LARGEST", title = "Large files"))
                }
            }
            TidyRow(R.drawable.ic_content_copy, "Check for exact copies") { go(DuplicatesRoute) }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column { content() }
    }
}

@Composable
private fun CountRow(@DrawableRes icon: Int, label: String, count: Long, line: String?, ofFiles: Boolean = true, onClick: () -> Unit) {
    // At large text the count drops under the label, so a long name keeps its words whole.
    val big = LocalDensity.current.fontScale >= 1.5f
    val n = Format.count(count)
    val second = listOfNotNull(line, n.takeIf { big }).joinToString(" · ").ifEmpty { null }
    ListItem(
        leadingContent = { Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(label) },
        supportingContent = second?.let { { Text(it) } },
        trailingContent = if (big) null else ({ Text(n, style = MaterialTheme.typography.bodyMedium.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant) }),
        colors = transparent,
        modifier = Modifier
            .clickable(onClickLabel = "Open", onClick = onClick)
            .semantics {
                val counted = if (!ofFiles) "$label, $n" else if (count == 1L) "$label, 1 file" else "$label, $n files"
                contentDescription = listOfNotNull(counted, line).joinToString(", ")
            },
    )
}

@Composable
private fun TidyRow(@DrawableRes icon: Int, text: String, onClick: () -> Unit) {
    ListItem(
        leadingContent = { Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(text) },
        trailingContent = { Icon(painterResource(R.drawable.ic_keyboard_arrow_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = transparent,
        modifier = Modifier.clickable(onClickLabel = "Open", onClick = onClick),
    )
}
