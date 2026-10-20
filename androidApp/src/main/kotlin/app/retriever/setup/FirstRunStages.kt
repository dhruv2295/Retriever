package app.retriever.setup

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.zIndex
import app.retriever.R
import app.retriever.access.Photos
import app.retriever.core.DeviceWords
import app.retriever.core.Kind
import app.retriever.core.SampleFile
import app.retriever.core.SamplePile
import app.retriever.core.TasteProgress
import app.retriever.core.Words
import app.retriever.ui.components.FileTile
import app.retriever.ui.components.Muted
import app.retriever.ui.components.kindIcon
import kotlinx.coroutines.delay

/** The welcome holds the one access decision: the whole phone, or, quietly, a choice of what to include. */
@Composable
internal fun ColumnScope.Welcome(ui: SetupUi, allFilesOff: Boolean, actions: SetupActions) {
    Text(Words.WELCOME_TITLE, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    Text(Words.welcomeBody(DeviceWords.ANDROID), style = MaterialTheme.typography.bodyLarge)
    WelcomeExamples(ui.textOnly)
    if (ui.access.allFiles) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(Words.WHOLE_PHONE_READY, style = MaterialTheme.typography.bodyMedium)
        }
        Button(onClick = actions.continueFromAccess, modifier = Modifier.fillMaxWidth()) { Text(Words.CONTINUE) }
    } else {
        Muted(Words.wholeDeviceNote(DeviceWords.ANDROID))
        Button(onClick = actions.allowFiles, modifier = Modifier.fillMaxWidth()) { Text(Words.SEARCH_WHOLE_PHONE) }
        if (allFilesOff) Muted(Words.ALL_FILES_OFF)
        TextButton(onClick = actions.chooseInstead, modifier = Modifier.fillMaxWidth()) { Text(Words.CHOOSE_INSTEAD) }
    }
    TextButton(onClick = actions.searchByName, modifier = Modifier.fillMaxWidth()) { Text(Words.SEARCH_BY_NAME) }
    if (ui.textOnly) Muted(Words.textOnlyNote(ui.ramGb, DeviceWords.ANDROID))
}

/** Choose what to include, in a sheet: media and folders, each opening its own system screen; Continue once any is given. */
@Composable
internal fun IncludeSheet(ui: SetupUi, asked: Boolean, folderError: String?, actions: SetupActions) {
    val a = ui.access
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(Words.INCLUDE_TITLE, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Column {
            AccessRow(R.drawable.ic_image, Words.PHOTOS_AND_VIDEOS, a.allFiles || (a.photos != Photos.NONE && a.videos), actions.allowMedia)
            AccessRow(kindIcon(Kind.AUDIO), Words.kindLabelPlural(Kind.AUDIO), a.allFiles || a.audio, actions.allowMedia)
            AccessRow(R.drawable.ic_folder_open, Words.ADD_FOLDER, false, actions.addFolder, a.folders.joinToString(" · ") { it.name }.ifEmpty { null })
        }
        if (asked && !a.ready) Muted(Words.NOTHING_SELECTED)
        folderError?.let { ErrorText(it) }
        Button(onClick = actions.continueFromAccess, enabled = a.ready, modifier = Modifier.fillMaxWidth()) { Text(Words.CONTINUE) }
    }
}

@Composable
private fun AccessRow(icon: Int, label: String, given: Boolean, onClick: () -> Unit, supporting: String? = null) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        trailingContent = {
            Icon(
                painterResource(if (given) R.drawable.ic_check_circle else R.drawable.ic_keyboard_arrow_right),
                contentDescription = if (given) "Allowed" else null,
                tint = if (given) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    )
}

/** The sample: the retriever's pile of what has been read, the count, one muted line, and See all. */
@Composable
internal fun ColumnScope.SampleStage(ui: SetupUi, taste: TasteProgress, actions: SetupActions, reduceMotion: Boolean) {
    val planned = taste.byKind.values.sumOf { it.second }.toLong()
    // The plan's progress per kind is said, not drawn.
    val spoken = (listOf(Words.SAMPLE_TITLE) + taste.byKind.map { (k, n) -> Words.kindRead(n.first.toLong(), n.second.toLong(), k) }).joinToString(", ")
    Text(
        Words.SAMPLE_TITLE,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics {
            heading()
            contentDescription = spoken
        },
    )
    // Before the plan exists (it syncs the library first), there is nothing to count or see yet: the sample is starting.
    val count = if (planned == 0L || (taste.read == 0 && taste.pending > 0)) Words.STARTING else Words.readOf(taste.read.toLong(), planned)
    val kinds = (taste.byKind.keys.toList() + listOf(Kind.PHOTO, Kind.PDF, Kind.DOC)).distinct().take(3)
    // A new pile at the first delivery, so the files already read then never replay.
    key(ui.sample == null) {
        SamplePile(SamplePile.shown(ui.sample.orEmpty()), kinds, reduceMotion, count, Words.seeAll(planned), actions.seeAll.takeIf { planned > 0 })
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(count, style = MaterialTheme.typography.bodyLarge)
        // The count line says it; the bar fills when nothing is left, Name only files included.
        if (planned > 0) {
            LinearProgressIndicator(
                progress = { (planned - taste.pending).toFloat() / planned },
                modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
            )
        }
    }
    when {
        taste.pending == 0 -> Unit
        ui.pauseReason != null -> Muted(ui.pauseReason)
        else -> SampleTime(ui.reading, taste.read.toLong(), ui.sampleStartedAt)
    }
    if (planned > 0) TextButton(onClick = actions.seeAll) { Text(Words.seeAll(planned)) }
}

/** Core's time line while the sample reads, which turns to "taking longer" once the usual time has passed. */
@Composable
private fun SampleTime(reading: Boolean, read: Long, startedAt: Long) {
    // Only this line redraws as time passes, and only while the sample reads.
    val elapsed by produceState(0L, reading, startedAt) {
        while (reading && startedAt > 0) {
            value = System.currentTimeMillis() - startedAt
            delay(TIME_TICK_MS)
        }
    }
    Words.sampleTime(reading, read, elapsed, DeviceWords.ANDROID)?.let { Muted(it) }
}

/**
 * The pile: the last [SamplePile.SHOWN] files read, each at its plan index's slot, so a card never moves once it
 * lands; with none yet, three placeholders of the plan's kinds. One node for a screen reader: the count, and See all
 * once there is a plan to see.
 */
@Composable
internal fun SamplePile(cards: List<SampleFile>, kinds: List<Kind>, reduceMotion: Boolean, label: String, seeAllLabel: String, onSeeAll: (() -> Unit)?) {
    // Only a card above the pile's top at first sight settles, so reopening the screen never replays the pile.
    val firstTop = remember { cards.maxOfOrNull { it.index } ?: -1 }
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val footprint = min(SamplePile.FOOTPRINT_DP.dp, maxWidth)
        val card = footprint * SamplePile.CARD_DP / SamplePile.FOOTPRINT_DP
        Box(
            Modifier.size(footprint)
                .then(if (onSeeAll == null) Modifier else Modifier.clickable(role = Role.Button, onClickLabel = seeAllLabel, onClick = onSeeAll))
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            if (cards.isEmpty()) {
                kinds.forEachIndexed { i, kind ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.medium,
                        shadowElevation = 2.dp,
                        modifier = Modifier.zIndex((kinds.size - i).toFloat()).graphicsLayer { slot(i, 0f) }.size(card).clearAndSetSemantics {},
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painterResource(kindIcon(kind)),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(48.dp),
                            )
                        }
                    }
                }
            }
            for (c in cards) key(c.index) { PileCard(c, card, animate = !reduceMotion && c.index > firstTop) }
        }
    }
}

/** A card settles from above, tilted and faded, onto its slot; with motion reduced, or already shown, it is simply there. */
@Composable
private fun PileCard(c: SampleFile, size: Dp, animate: Boolean) {
    val landed = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) { landed.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)) }
    FileTile(c.file, size, Modifier.zIndex(c.index.toFloat()).graphicsLayer { slot(c.index, 1f - landed.value) }.testTag("card:${c.index}"))
}

/** [i]'s slot on the pile; [away] 1 is the settle's start: 48 dp above, 8° more turned, transparent. */
private fun GraphicsLayerScope.slot(i: Int, away: Float) {
    rotationZ = SamplePile.angle(i) + SETTLE_TURN * away
    translationX = SamplePile.dx(i).dp.toPx()
    translationY = (SamplePile.dy(i).dp - SETTLE_DROP * away).toPx()
    alpha = (1f - away).coerceIn(0f, 1f)
}

/** The sample's footer: Try search, enabled once enough is read, and the way to Home throughout. */
@Composable
internal fun SampleFooter(canTry: Boolean, actions: SetupActions) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onClick = actions.tryNow, enabled = canTry, modifier = Modifier.fillMaxWidth()) { Text(Words.TRY_SEARCH) }
        TextButton(onClick = actions.skip, modifier = Modifier.fillMaxWidth()) { Text(Words.GO_TO_HOME) }
    }
}

private const val TIME_TICK_MS = 15_000L

private const val SETTLE_TURN = 8f

private val SETTLE_DROP = 48.dp

@Composable
internal fun ErrorText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}
