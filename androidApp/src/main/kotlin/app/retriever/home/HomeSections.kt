package app.retriever.home

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.retriever.R
import app.retriever.core.FileTotals
import app.retriever.core.HomeFacts
import app.retriever.core.Kind
import app.retriever.core.SourceFile
import app.retriever.core.Words
import app.retriever.core.YearMemory
import app.retriever.files.FilePressMenu
import app.retriever.nav.BrowseRoute
import app.retriever.nav.FoldersRoute
import app.retriever.nav.Route
import app.retriever.search.SearchActions
import app.retriever.ui.Format
import app.retriever.ui.components.ThumbnailImage
import app.retriever.ui.components.kindIcon
import app.retriever.ui.tabular
import app.retriever.viewer.Opener
import java.time.LocalDate
import java.time.ZoneId

// Home's picture sections: header doorway, chip row, mosaic, Screenshots and Documents strips. Colour tokens only,
// except the white text, black scrims and video badge that sit on photos.

private val StripPadding = PaddingValues(horizontal = 16.dp)
private val MosaicGap = 4.dp
private val WideCell = 124.dp

/**
 * A section's title with its count (or readiness) beneath and an arrow: the whole header opens the list when
 * [onClick] is given. Without it the header is only a heading.
 */
@Composable
internal fun SectionHeader(title: String, subtitle: String? = null, onClick: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = "Show all", onClick = onClick) else Modifier)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp)
            .semantics(mergeDescendants = true) {
                heading()
                // Spoken with commas, not the visual dot: "Screenshots, 158, 40 waiting to read".
                contentDescription = if (subtitle == null) title else "$title, ${subtitle.replace(" · ", ", ")}"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall.tabular(), color = colors.onSurfaceVariant)
        }
        if (onClick != null) {
            Icon(painterResource(R.drawable.ic_keyboard_arrow_right), contentDescription = null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
        }
    }
}

/** "158" when nothing waits, "158 · 40 waiting to read" while files do, so a section says where it stands. */
internal fun counted(count: String, readiness: String?) = if (readiness == null) count else "$count · $readiness"

/** One row of the person's last searches, or, before any, a few things worth trying; [fill] puts one in the field and runs it. */
@Composable
internal fun ChipRow(recent: List<String>, suggestions: List<String>, fill: (String, Boolean) -> Unit) {
    val trying = recent.isEmpty()
    LazyRow(contentPadding = StripPadding, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (trying) {
            item(key = 0) { Text("Try searching", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(if (trying) suggestions else recent, key = { it }) { q ->
            SuggestionChip(
                onClick = { fill(q, true) },
                label = { Text(q, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                icon = { Icon(painterResource(if (trying) R.drawable.ic_search else R.drawable.ic_history), contentDescription = null, Modifier.size(18.dp)) },
                modifier = Modifier.semantics {
                    if (trying) contentDescription = "Try searching $q"
                    onClick(label = "Searches again", action = null)
                },
            )
        }
    }
}

/** The mosaic under its header; every picture is shown while reading, with the pill saying how many photos are left. */
@Composable
internal fun MediaSection(ui: HomeUi, home: HomeFacts, actions: SearchActions) {
    val photos = ui.counts?.get(Kind.PHOTO) ?: 0L
    val videos = ui.counts?.get(Kind.VIDEO) ?: 0L
    val left = ui.pending[Kind.PHOTO] ?: 0L
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Photos and videos", ui.sized(Kind.PHOTO, Kind.VIDEO)) {
            actions.onNavigate(
                if (photos > 0 ||
                    videos == 0L
                ) {
                    BrowseRoute(kind = Kind.PHOTO.name, title = "Photos")
                } else {
                    BrowseRoute(kind = Kind.VIDEO.name, title = "Videos")
                },
            )
        }
        Mosaic(home.newMedia, MosaicReading(left, photos, ui.paused).takeIf { left > 0 && Kind.PHOTO in ui.inside }) { f ->
            Opener.queue(home.newMedia)
            actions.onOpenFile(f)
        }
    }
}

@Composable
internal fun ShotsSection(ui: HomeUi, home: HomeFacts, actions: SearchActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Screenshots", ui.sized(Kind.SCREENSHOT)?.let { counted(it, ui.readiness(Kind.SCREENSHOT)) }) {
            actions.onNavigate(BrowseRoute(kind = Kind.SCREENSHOT.name, title = Words.kindLabelPlural(Kind.SCREENSHOT)))
        }
        ShotsStrip(home.newShots) { f ->
            Opener.queue(home.newShots)
            actions.onOpenFile(f)
        }
    }
}

@Composable
internal fun DocsSection(ui: HomeUi, home: HomeFacts, actions: SearchActions) {
    val pdfs = ui.counts?.get(Kind.PDF) ?: 0L
    val docs = ui.counts?.get(Kind.DOC) ?: 0L
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(
            Words.documentsTitle(pdfs, docs),
            ui.sized(Kind.PDF, Kind.DOC)?.let { counted(it, ui.readiness(Kind.PDF, Kind.DOC)) },
        ) {
            val kind = if (pdfs > 0) Kind.PDF else Kind.DOC
            actions.onNavigate(BrowseRoute(kind = kind.name, title = Words.kindLabelPlural(kind)))
        }
        DocsStrip(home.newDocs, ui.hasAccess, actions.onOpenFile)
    }
}

/** Photos still to read, for the pill and the line on the mosaic's big cell. */
@Immutable
internal data class MosaicReading(val pending: Long, val total: Long, val paused: Boolean) {
    val progress get() = if (total > 0) (1f - pending.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/**
 * The newest pictures as one block: the newest large (2 x 2), the next two stacked beside it, then a row. It collapses
 * with fewer: one wide cell, two squares, or the top block alone for three to five. On a wide window the block gets
 * more columns (3 per 124 dp, up to 6), always 2 x 2 for the first. [onOpen] gets the tapped file.
 */
@Composable
internal fun Mosaic(media: List<SourceFile>, reading: MosaicReading?, onOpen: (SourceFile) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val cols = (maxWidth / WideCell).toInt().coerceIn(3, 6)
        val cell = (maxWidth - MosaicGap * (cols - 1)) / cols
        val big = cell * 2 + MosaicGap
        val full = 3 * cols - 3
        val stacked = 1 + 2 * (cols - 2)
        // A stack is two cells; an odd leftover would leave a hole, so a short list stops at a whole stack.
        val shown = when {
            media.size >= full -> media.take(full)
            media.size <= 2 -> media
            else -> media.take(minOf(stacked, 1 + 2 * ((media.size - 1) / 2)))
        }
        val half = (maxWidth - MosaicGap) / 2
        val wide = maxWidth * 9 / 16
        when (shown.size) {
            0 -> Unit

            1 -> MosaicCell(shown[0], maxWidth, wide, 20.dp, reading, onOpen)

            2 -> Row(horizontalArrangement = Arrangement.spacedBy(MosaicGap)) {
                for (f in shown) key(f.uri) { MosaicCell(f, half, half, 20.dp, null, onOpen) }
            }

            else -> Column(verticalArrangement = Arrangement.spacedBy(MosaicGap)) {
                Row(horizontalArrangement = Arrangement.spacedBy(MosaicGap)) {
                    key(shown[0].uri) { MosaicCell(shown[0], big, big, 20.dp, reading, onOpen) }
                    for (stack in shown.take(stacked).drop(1).chunked(2)) {
                        Column(verticalArrangement = Arrangement.spacedBy(MosaicGap)) {
                            for (f in stack) key(f.uri) { MosaicCell(f, cell, cell, 12.dp, null, onOpen) }
                        }
                    }
                }
                if (shown.size > stacked) {
                    Row(horizontalArrangement = Arrangement.spacedBy(MosaicGap)) {
                        for (f in shown.drop(stacked)) key(f.uri) { MosaicCell(f, cell, cell, 12.dp, null, onOpen) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MosaicCell(file: SourceFile, w: Dp, h: Dp, corner: Dp, reading: MosaicReading?, onOpen: (SourceFile) -> Unit) {
    val context = LocalContext.current
    val label = remember(file, reading?.pending) {
        val base = "${Words.kindLabel(file.kind)}, ${spokenDate(context, file.takenAt ?: file.modifiedAt)}"
        if (reading != null && reading.pending > 0) "$base, ${Words.waitingToRead(reading.pending)}" else base
    }
    FilePressMenu(file) { menu, actions ->
        Box(
            Modifier
                .size(w, h)
                .clip(RoundedCornerShape(corner))
                .semantics(mergeDescendants = true) { contentDescription = label }
                .openOrMenu(menu, actions) { onOpen(file) },
        ) {
            ThumbnailImage(file, maxOf(w, h), Modifier.fillMaxSize())
            if (file.kind == Kind.VIDEO) VideoBadge(Modifier.align(if (reading != null) Alignment.BottomEnd else Alignment.BottomStart))
            if (reading != null) ReadingPill(reading)
        }
    }
}

/** Tap opens; a long press opens the file menu Search and Browse use, its actions also offered to a screen reader. */
private fun Modifier.openOrMenu(menu: () -> Unit, actions: Modifier, onOpen: () -> Unit) =
    combinedClickable(onClickLabel = "Open", onLongClickLabel = "More options", onLongClick = menu, onClick = onOpen).then(actions)

/** A play mark that stays black and white on any picture. */
@Composable
private fun VideoBadge(modifier: Modifier = Modifier) {
    Box(modifier.padding(6.dp).size(28.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_play_arrow), contentDescription = null, Modifier.size(18.dp), tint = Color.White)
    }
}

/** "14,700 waiting to read" on a scrim along the big cell's bottom, with a 3 dp line of how much is read. */
@Composable
private fun BoxScope.ReadingPill(reading: MosaicReading) {
    Box(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.4f)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))),
    )
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = CircleShape,
        modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 14.dp),
    ) {
        Text(
            Words.waitingToRead(reading.pending) + if (reading.paused) " · Paused" else "",
            style = MaterialTheme.typography.labelLarge.tabular(),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.3f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(reading.progress).background(MaterialTheme.colorScheme.primary))
    }
}

/** Newest screenshots as phone-shaped (9:16) cards; the last is cut by the edge so the strip reads as scrollable. */
@Composable
internal fun ShotsStrip(shots: List<SourceFile>, onOpen: (SourceFile) -> Unit) {
    val context = LocalContext.current
    val outline = MaterialTheme.colorScheme.outlineVariant
    LazyRow(contentPadding = StripPadding, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(shots, key = { it.uri }, contentType = { "shot" }) { f ->
            val shape = RoundedCornerShape(16.dp)
            FilePressMenu(f) { menu, actions ->
                ThumbnailImage(
                    f,
                    171.dp,
                    Modifier
                        .size(96.dp, 171.dp)
                        .clip(shape)
                        .border(1.dp, outline, shape)
                        .semantics(mergeDescendants = true) { contentDescription = "Screenshot, ${spokenDate(context, f.takenAt ?: f.modifiedAt)}" }
                        .openOrMenu(menu, actions) { onOpen(f) },
                )
            }
        }
    }
}

/** Newest PDFs and documents as page cards with the name and "Today · 2.1 MB" under each. [pictures] false (no access) keeps icons. */
@Composable
internal fun DocsStrip(docs: List<SourceFile>, pictures: Boolean, onOpen: (SourceFile) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val now = remember { System.currentTimeMillis() }
    LazyRow(contentPadding = StripPadding, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(docs, key = { it.uri }, contentType = { "doc" }) { f ->
            val line = remember(f, now) { Format.fileLine(f.copy(folder = ""), now) }
            val shape = RoundedCornerShape(12.dp)
            FilePressMenu(f) { menu, actions ->
                Column(
                    Modifier
                        .width(112.dp)
                        .clip(shape)
                        .semantics(mergeDescendants = true) { contentDescription = "${Words.kindLabel(f.kind)}, ${f.name}, ${line.replace(" · ", ", ")}" }
                        .openOrMenu(menu, actions) { onOpen(f) },
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // A PDF's first page; a document's own text (the thumbnail's text tile).
                    ThumbnailImage(
                        f,
                        150.dp,
                        Modifier.size(112.dp, 150.dp).clip(shape).border(1.dp, colors.outlineVariant, shape),
                        load = pictures,
                        container = colors.surfaceContainerLowest,
                    )
                    Text(f.name, style = MaterialTheme.typography.bodySmall, color = colors.onSurface, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
                    Text(line, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

private fun spokenDate(context: Context, ms: Long): String = DateUtils.formatDateTime(context, ms, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)

// --- On this day, Starred, More on your phone ---

/** "8 photos", "1 video", "5 photos and videos". */
internal fun memoryCount(m: YearMemory): String = when {
    m.videos == 0L -> Words.plural(m.photos, "photo")
    m.photos == 0L -> Words.plural(m.videos, "video")
    else -> "${Format.count(m.photos + m.videos)} photos and videos"
}

/** Past years' photos on today's date, one card per year. The header is only a heading: each card opens its day. */
@Composable
internal fun OnThisDaySection(day: LocalDate, memories: List<YearMemory>, go: (Route) -> Unit, onHide: () -> Unit) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val monthDay = remember(day) { dayText(context, day, zone, year = false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("On this day", monthDay)
        LazyRow(contentPadding = StripPadding, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(memories, key = { it.year }, contentType = { "year" }) { m ->
                val count = memoryCount(m)
                val shape = RoundedCornerShape(20.dp)
                Box(
                    Modifier
                        .size(144.dp, 192.dp)
                        .clip(shape)
                        .semantics(mergeDescendants = true) { contentDescription = "${m.year}, $count from $monthDay" }
                        .combinedClickable(
                            onClickLabel = "Show them",
                            onLongClickLabel = "Hide On this day",
                            onLongClick = onHide,
                            onClick = {
                                val date = LocalDate.of(m.year, day.monthValue, day.dayOfMonth)
                                val (from, to) = dayRange(date, zone)
                                go(BrowseRoute(takenFrom = from, takenTo = to, title = dayText(context, date, zone, year = true)))
                            },
                        ),
                ) {
                    ThumbnailImage(m.cover, 192.dp, Modifier.fillMaxSize())
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.45f)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))),
                    )
                    Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                        Text(m.year.toString(), style = MaterialTheme.typography.headlineSmall, color = Color.White)
                        Text(count, style = MaterialTheme.typography.labelLarge, color = Color.White)
                    }
                }
            }
        }
    }
}

private fun dayText(context: Context, date: LocalDate, zone: ZoneId, year: Boolean): String = DateUtils.formatDateTime(
    context,
    date.atStartOfDay(zone).toInstant().toEpochMilli(),
    DateUtils.FORMAT_SHOW_DATE or if (year) DateUtils.FORMAT_SHOW_YEAR else DateUtils.FORMAT_NO_YEAR,
)

/** The most recently starred files as 112 dp tiles; the header opens the whole Starred list. */
@Composable
internal fun StarredSection(ui: HomeUi, home: HomeFacts, actions: SearchActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Starred", Format.count(home.starredCount)) { actions.onNavigate(BrowseRoute(title = "Starred", starredOnly = true)) }
        val colors = MaterialTheme.colorScheme
        LazyRow(contentPadding = StripPadding, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(home.starred, key = { it.uri }, contentType = { "starred" }) { f ->
                val shape = RoundedCornerShape(12.dp)
                // Documents and audio have no picture; without access none can load.
                val picture = ui.hasAccess && f.kind != Kind.DOC && f.kind != Kind.AUDIO
                FilePressMenu(f) { menu, menuActions ->
                    Box(
                        Modifier
                            .size(112.dp)
                            .clip(shape)
                            .semantics(mergeDescendants = true) { contentDescription = "${Words.kindLabel(f.kind)}, ${f.name}, starred" }
                            .openOrMenu(menu, menuActions) {
                                Opener.queue(home.starred)
                                actions.onOpenFile(f)
                            },
                    ) {
                        if (picture) {
                            ThumbnailImage(
                                f,
                                112.dp,
                                Modifier.fillMaxSize(),
                                container = if (f.kind ==
                                    Kind.PDF
                                ) {
                                    colors.surfaceContainerLowest
                                } else {
                                    Color.Unspecified
                                },
                            )
                        } else {
                            Column(Modifier.fillMaxSize().background(colors.surfaceContainer).padding(8.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                                Icon(painterResource(kindIcon(f.kind)), contentDescription = null, Modifier.size(28.dp), tint = colors.primary)
                                Text(
                                    f.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.MiddleEllipsis,
                                )
                            }
                        }
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).background(colors.surface.copy(alpha = 0.8f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(painterResource(R.drawable.ic_star), contentDescription = null, Modifier.size(16.dp), tint = colors.primary)
                        }
                    }
                }
            }
        }
    }
}

/** One tile of More on your phone. */
@Immutable
internal data class KindTile(val icon: Int, val label: String, val line: String, val spoken: String, val route: Route)

/** "120 files · 640 MB" for [kinds] together; null until the totals are loaded or with none of these files. */
internal fun HomeUi.sized(vararg kinds: Kind): String? {
    val t = kinds.mapNotNull(kindTotals::get).takeIf { it.isNotEmpty() } ?: return null
    return sized(FileTotals(t.sumOf { it.files }, t.sumOf { it.knownBytes }, t.sumOf { it.unknownSizes })).takeIf { t.any { it.files > 0 } }
}

private fun sized(t: FileTotals) = Words.countAndSize(t, Format.bytes(t.knownBytes))

/**
 * Every kind, then the folders with the most files, each with count and size, then All folders. Kinds and folders are
 * two views of the same files, not totals to add.
 */
internal fun moreTiles(ui: HomeUi): List<KindTile> {
    val kinds = libraryKinds.mapNotNull { k ->
        val t = ui.kindTotals[k]?.takeIf { it.files > 0 } ?: return@mapNotNull null
        val label = Words.kindLabelPlural(k)
        tile(kindIcon(k), label, counted(sized(t), ui.readiness(k)), BrowseRoute(kind = k.name, title = label))
    }
    val folders = ui.folderTotals.take(Words.MORE_FOLDERS).map { f ->
        val name = f.path.trimEnd('/').substringAfterLast('/').ifBlank { "Files" }
        tile(R.drawable.ic_folder, name, sized(f.totals), BrowseRoute(folder = f.path, title = name))
    }
    val all = ui.folderTotals.size.toLong().takeIf {
        it > 0
    }?.let { tile(R.drawable.ic_folder_open, Words.ALL_FOLDERS, Words.plural(it, "folder"), FoldersRoute) }
    return kinds + folders + listOfNotNull(all)
}

private fun tile(icon: Int, label: String, line: String, route: Route) = KindTile(icon, label, line, "$label, ${line.replace(" · ", ", ")}", route)

/** One list: kinds and folders with count and size, each row opening its files. */
@Composable
internal fun MoreSection(tiles: List<KindTile>, go: (Route) -> Unit) {
    Column {
        SectionHeader(Words.MORE_ON_PHONE)
        Text(Words.MORE_ON_PHONE_BODY, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
        for (tile in tiles) {
            androidx.compose.material3.ListItem(
                headlineContent = { Text(tile.label) },
                supportingContent = { Text(tile.line, style = MaterialTheme.typography.bodyMedium.tabular()) },
                leadingContent = { Icon(painterResource(tile.icon), contentDescription = null) },
                trailingContent = { Icon(painterResource(R.drawable.ic_keyboard_arrow_right), contentDescription = null) },
                modifier = Modifier.clickable(onClickLabel = "Show all") { go(tile.route) }.semantics { contentDescription = tile.spoken },
            )
        }
    }
}
