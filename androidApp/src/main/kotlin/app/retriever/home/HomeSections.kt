package app.retriever.home

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.dp
import app.retriever.BuildConfig
import app.retriever.R
import app.retriever.core.BrowseKind
import app.retriever.core.HomeFacts
import app.retriever.core.Kind
import app.retriever.core.Person
import app.retriever.core.SamePerson
import app.retriever.core.SourceFile
import app.retriever.core.Words
import app.retriever.core.YearMemory
import app.retriever.nav.BrowseRoute
import app.retriever.nav.DuplicatesRoute
import app.retriever.nav.FoldersRoute
import app.retriever.nav.Route
import app.retriever.nav.route
import app.retriever.search.SearchActions
import app.retriever.ui.Format
import app.retriever.ui.components.FaceAvatar
import app.retriever.ui.components.FileTile
import app.retriever.ui.components.Muted
import app.retriever.ui.components.ThumbnailImage
import app.retriever.ui.components.kindIcon
import app.retriever.ui.components.onRightClick
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
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = Words.strings.showAll, onClick = onClick) else Modifier)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp)
            .semantics(mergeDescendants = true) {
                heading()
                contentDescription = if (subtitle == null) title else Words.strings.sectionSpoken(title, subtitle)
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

/**
 * A header over one strip of [FileTile]s, newest first; the header opens the whole list, a tile opens the file among the
 * strip's. [origin] keys its photos' morph apart from the other strip's, which may hold the same photo.
 */
@Composable
private fun FileStrip(title: String, origin: String, files: List<SourceFile>, onHeader: () -> Unit, actions: SearchActions, load: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(title, onClick = onHeader)
        LazyRow(contentPadding = StripPadding, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(files, key = { it.uri }, contentType = { "tile" }) { f ->
                FileTile(f, TILE, morph = actions.morph, origin = origin, load = load) {
                    Opener.queue(files)
                    actions.onOpenFile(it, origin)
                }
            }
        }
    }
}

/** The newest photos, videos, screenshots and documents as one strip of small tiles, newest first; the header opens them all. */
@Composable
internal fun RecentlyAddedSection(files: List<SourceFile>, actions: SearchActions) =
    FileStrip(Words.RECENTLY_ADDED, "recent", files, { actions.onNavigate(BrowseRoute(title = Words.RECENTLY_ADDED)) }, actions)

/**
 * People while it is off: Settings' switch asked for where the section will appear, since off by default and only in
 * Settings, nobody found it. Turn on fills this spot with People and its faces count; Not now leaves only the switch.
 */
@Composable
internal fun PeopleOffer(turnOn: () -> Unit, notNow: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(Words.PEOPLE)
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(Words.PEOPLE_OFFER_TITLE, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                Text(Words.PEOPLE_OFFER_BODY, style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = turnOn) { Text(Words.TURN_ON) }
                    TextButton(onClick = notNow) { Text(Words.NOT_NOW) }
                }
            }
        }
    }
}

@Composable
internal fun NotificationsOffer(turnOn: () -> Unit, notNow: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(Words.strings.showProcessingProgress, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(Words.strings.processingProgressNotice, style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = turnOn) { Text(Words.TURN_ON) }
                TextButton(onClick = notNow) { Text(Words.NOT_NOW) }
            }
        }
    }
}

/**
 * Faces grouped so far, as round crops with their names; while photos wait, the count to go. Tap opens the person's photos.
 * Below, when people may be one ([samePeople]), the question, one pair at a time. Debug builds number the tiles so a
 * tester can name them.
 */
@Composable
internal fun PeopleSection(
    people: List<Person>,
    facesLeft: Long,
    go: (Route) -> Unit,
    samePeople: List<SamePerson> = emptyList(),
    answer: (SamePerson, same: Boolean) -> Unit = { _, _ -> },
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(Words.PEOPLE, subtitle = if (facesLeft > 0) Words.facesToGo(facesLeft) else null)
        if (people.isNotEmpty()) {
            LazyRow(contentPadding = StripPadding, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(people, key = { _, p -> p.id }, contentType = { _, _ -> "person" }) { i, p ->
                    val title = Words.personTitle(p.name)
                    Column(
                        Modifier
                            .width(TILE)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button, onClickLabel = Words.strings.openAction) { go(BrowseRoute(title = title, person = p.id)) }
                            .semantics(mergeDescendants = true) { contentDescription = Words.strings.personTileSpoken(title, p.photos.toLong()) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (BuildConfig.DEBUG) Muted("${i + 1}")
                        FaceAvatar(p.cover, p.name, TILE - 16.dp)
                        Muted(title, maxLines = 1)
                    }
                }
            }
        }
        if (samePeople.isNotEmpty()) SamePersonCard(samePeople, answer) { q -> people.indexOfFirst { it.id == q.id } + 1 }
    }
}

/** One pair at a time, closest first; previous and next leave a pair the person isn't sure about unanswered. */
@Composable
private fun SamePersonCard(pairs: List<SamePerson>, answer: (SamePerson, same: Boolean) -> Unit, tile: (Person) -> Int) {
    var shown by rememberSaveable { mutableIntStateOf(0) }
    // An answered pair leaves the list, so the same place shows the next one.
    val at = shown.coerceAtMost(pairs.lastIndex)
    val q = pairs[at]
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
            if (BuildConfig.DEBUG) Muted("Tiles ${tile(q.first)} and ${tile(q.second)}")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FaceAvatar(q.first.cover, q.first.name, SAME_PERSON_FACE)
                FaceAvatar(q.second.cover, q.second.name, SAME_PERSON_FACE)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(Words.SAME_PERSON_ASK, style = MaterialTheme.typography.titleMedium)
                    if (pairs.size > 1) Muted(Words.pairOf(at + 1, pairs.size))
                }
            }
            // Large text wraps the answers below the arrows.
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, itemVerticalAlignment = Alignment.CenterVertically) {
                Row {
                    if (pairs.size > 1) {
                        IconButton(onClick = { shown = at - 1 }, enabled = at > 0) {
                            Icon(painterResource(R.drawable.ic_keyboard_arrow_left), contentDescription = Words.SAME_PERSON_PREVIOUS)
                        }
                        IconButton(onClick = { shown = at + 1 }, enabled = at < pairs.lastIndex) {
                            Icon(painterResource(R.drawable.ic_keyboard_arrow_right), contentDescription = Words.SAME_PERSON_NEXT)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { answer(q, false) }) { Text(Words.SAME_PERSON_NO) }
                    FilledTonalButton(onClick = { answer(q, true) }) { Text(Words.SAME_PERSON_YES) }
                }
            }
        }
    }
}

private val SAME_PERSON_FACE = 56.dp

/**
 * Browse: each kind with its count, then Folders; three a row, two at large text. Documents holds PDFs too and opens the
 * PDFs when there are any (Browse lists one kind at a time).
 */
@Composable
internal fun BrowseSection(kinds: List<BrowseKind>, folders: Long, go: (Route) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(Words.MORE_ON_PHONE)
        val columns = if (LocalDensity.current.fontScale > LARGE_TEXT) 2 else 3
        val n = kinds.size + if (folders > 0) 1 else 0
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            Arrangement.spacedBy(8.dp),
            Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = columns,
        ) {
            for (k in kinds) BrowseTile(kindIcon(k.kinds.first()), k.title, Format.count(k.files), { go(k.route()) }, Modifier.weight(1f))
            if (folders > 0) BrowseTile(R.drawable.ic_folder, Words.strings.folders, Format.count(folders), { go(FoldersRoute) }, Modifier.weight(1f))
            // The last row keeps the others' width.
            repeat((columns - n % columns) % columns) { Spacer(Modifier.weight(1f)) }
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
        modifier = modifier.heightIn(min = 72.dp).semantics(mergeDescendants = true) { contentDescription = Words.strings.browseTileSpoken(label, count) },
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
internal fun memoryCount(m: YearMemory): String = Words.memoryCount(m.photos, m.videos)

/** Past years' photos on today's date, one small tile per year, the date always in the header. Each tile opens its day. */
@Composable
internal fun OnThisDaySection(day: LocalDate, memories: List<YearMemory>, go: (Route) -> Unit, onHide: () -> Unit) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val monthDay = remember(day) { dayText(context, day, zone, year = false) }
    val tile = tileSize()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(Words.strings.onThisDay, monthDay)
        LazyRow(contentPadding = StripPadding, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(memories, key = { it.year }, contentType = { "year" }) { m ->
                val count = memoryCount(m)
                val shape = RoundedCornerShape(12.dp)
                Box(
                    Modifier
                        .size(tile)
                        .clip(shape)
                        .semantics(mergeDescendants = true) { contentDescription = Words.strings.onThisDaySpoken(m.year, count, monthDay) }
                        .onRightClick(onHide)
                        .combinedClickable(
                            onClickLabel = Words.strings.showThem,
                            onLongClickLabel = Words.strings.hideOnThisDay,
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

/** The most recently starred files as the same small tiles; the header opens the whole Starred list. */
@Composable
internal fun StarredSection(hasAccess: Boolean, home: HomeFacts, actions: SearchActions) = FileStrip(Words.strings.starredSection, "starred", home.starred, {
    actions.onNavigate(BrowseRoute(title = Words.strings.starredSection, starredOnly = true))
}, actions, hasAccess)
