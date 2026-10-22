package app.retriever.home

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.retriever.R
import app.retriever.core.BrowseKind
import app.retriever.core.HomeFacts
import app.retriever.core.Kind
import app.retriever.core.SourceFile
import app.retriever.core.Words
import app.retriever.core.YearMemory
import app.retriever.files.FilePressMenu
import app.retriever.nav.BrowseRoute
import app.retriever.nav.DuplicatesRoute
import app.retriever.nav.FoldersRoute
import app.retriever.nav.Route
import app.retriever.search.SearchActions
import app.retriever.ui.Format
import app.retriever.ui.components.FileTile
import app.retriever.ui.components.Muted
import app.retriever.ui.components.ThumbnailImage
import app.retriever.ui.components.kindIcon
import app.retriever.ui.tabular
import app.retriever.viewer.Opener
import java.time.LocalDate
import java.time.ZoneId

// Home's sections: Starred, Recently added, On this day, Browse and Free up space. Small tiles only: no private picture full-width.
// Colour tokens only, except the white text and black scrim that sit on photos.

private val StripPadding = PaddingValues(horizontal = 16.dp)

/** Tile edge on Home's strips. */
private val TILE = 96.dp

/** Font scale past which Browse has two tiles a row, not three. */
private const val LARGE_TEXT = 1.3f

/**
 * A section's title with an optional line beneath and an arrow: the whole header opens the list when [onClick] is given.
 * Without it the header is only a heading.
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
                contentDescription = if (subtitle == null) title else "$title, $subtitle"
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

/** Tap opens; a long press opens the file menu Search and Browse use, its actions also offered to a screen reader. */
private fun Modifier.openOrMenu(menu: () -> Unit, actions: Modifier, onOpen: () -> Unit) =
    combinedClickable(onClickLabel = "Open", onLongClickLabel = "More options", onLongClick = menu, onClick = onOpen).then(actions)

/** The newest photos, videos, screenshots and documents as one strip of small tiles, newest first; the header opens them all. */
@Composable
internal fun RecentlyAddedSection(files: List<SourceFile>, actions: SearchActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(Words.RECENTLY_ADDED) { actions.onNavigate(BrowseRoute(title = Words.RECENTLY_ADDED)) }
        LazyRow(contentPadding = StripPadding, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(files, key = { it.uri }, contentType = { "tile" }) { f ->
                FileTile(f, TILE) {
                    Opener.queue(files)
                    actions.onOpenFile(it)
                }
            }
        }
    }
}

/**
 * Browse: each kind with its count, then Folders; three a row, two at large text. Documents holds PDFs too and opens the
 * PDFs when there are any (Browse lists one kind at a time).
 */
@Composable
internal fun BrowseSection(kinds: List<BrowseKind>, folders: Long, go: (Route) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(Words.MORE_ON_PHONE)
        val columns = if (LocalDensity.current.fontScale > LARGE_TEXT) 2 else 3
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            val gap = 8.dp
            // Whole dp, rounded down: a fraction rounded up per tile would push the last one onto its own row.
            val cell = ((maxWidth - gap * (columns - 1)) / columns).value.toInt().dp
            FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap), maxItemsInEachRow = columns) {
                for (k in kinds) {
                    val kind = k.kinds.firstOrNull { it == Kind.PDF } ?: k.kinds.first()
                    BrowseTile(kindIcon(k.kinds.first()), k.title, Format.count(k.files), {
                        go(BrowseRoute(kind = kind.name, title = k.title))
                    }, Modifier.width(cell))
                }
                if (folders > 0) BrowseTile(R.drawable.ic_folder, "Folders", Format.count(folders), { go(FoldersRoute) }, Modifier.width(cell))
            }
        }
    }
}

/** Home's last section: large files and possible copies, a row each only when there are some. */
@Composable
internal fun FreeUpSpaceSection(facts: HomeFacts, go: (Route) -> Unit) {
    Column {
        SectionHeader(Words.FREE_UP_SPACE)
        if (facts.largeCount > 0) {
            ToolRow(R.drawable.ic_storage, Words.LARGE_FILES, Words.largeFilesDetail(facts.largeCount, Format.bytes(facts.largeBytes))) {
                go(BrowseRoute(sort = "LARGEST", title = Words.LARGE_FILES))
            }
        }
        if (facts.possibleCopies > 0) {
            ToolRow(R.drawable.ic_file_copy, Words.FIND_DUPLICATES, Words.possibleCopies(facts.possibleCopies)) { go(DuplicatesRoute) }
        }
        Muted(Words.NEVER_DELETES, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    }
}

@Composable
private fun ToolRow(icon: Int, title: String, detail: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail, style = MaterialTheme.typography.bodyMedium.tabular()) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(painterResource(R.drawable.ic_keyboard_arrow_right), contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    )
}

@Composable
private fun BrowseTile(icon: Int, label: String, count: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.heightIn(min = 72.dp).semantics(mergeDescendants = true) { contentDescription = "$label, $count" },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(count, style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A strip's square tile: 112 dp, grown with the text size so its words fit at large text. */
@Composable
@ReadOnlyComposable
private fun tileSize() = 112.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)

/** "8 photos", "1 video", "5 photos and videos". */
internal fun memoryCount(m: YearMemory): String = when {
    m.videos == 0L -> Words.plural(m.photos, "photo")
    m.photos == 0L -> Words.plural(m.videos, "video")
    else -> "${Format.count(m.photos + m.videos)} photos and videos"
}

/** Past years' photos on today's date, one small tile per year, the date always in the header. Each tile opens its day. */
@Composable
internal fun OnThisDaySection(day: LocalDate, memories: List<YearMemory>, go: (Route) -> Unit, onHide: () -> Unit) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val monthDay = remember(day) { dayText(context, day, zone, year = false) }
    val tile = tileSize()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("On this day", monthDay)
        LazyRow(contentPadding = StripPadding, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(memories, key = { it.year }, contentType = { "year" }) { m ->
                val count = memoryCount(m)
                val shape = RoundedCornerShape(12.dp)
                Box(
                    Modifier
                        .size(tile)
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
                    ThumbnailImage(m.cover, tile, Modifier.fillMaxSize())
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.5f)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))),
                    )
                    Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
                        Text(m.year.toString(), style = MaterialTheme.typography.titleMedium, color = Color.White)
                        Text(count, style = MaterialTheme.typography.labelSmall, color = Color.White)
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
    val tile = tileSize()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Starred") { actions.onNavigate(BrowseRoute(title = "Starred", starredOnly = true)) }
        val colors = MaterialTheme.colorScheme
        LazyRow(contentPadding = StripPadding, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(home.starred, key = { it.uri }, contentType = { "starred" }) { f ->
                val shape = RoundedCornerShape(12.dp)
                // Documents and audio have no picture; without access none can load.
                val picture = ui.hasAccess && f.kind != Kind.DOC && f.kind != Kind.AUDIO
                FilePressMenu(f) { menu, menuActions ->
                    Box(
                        Modifier
                            .size(tile)
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
                                tile,
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
