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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.zIndex
import app.retriever.R
import app.retriever.access.ONLY_PICKED_PHOTOS
import app.retriever.access.Photos
import app.retriever.core.DeviceWords
import app.retriever.core.Kind
import app.retriever.core.SampleFile
import app.retriever.core.SamplePile
import app.retriever.core.Words
import app.retriever.motion.motionAllowed
import app.retriever.ui.Format
import app.retriever.ui.components.FileTile
import app.retriever.ui.components.Muted
import app.retriever.ui.components.kindIcon

/** Hello: what Retriever does, said once, and an example of it. Get started is pinned below ([HelloActions]). */
@Composable
internal fun ColumnScope.Hello(ui: SetupUi, reduceMotion: Boolean) {
    Heading(Words.helloTitle(ui.textOnly), Words.HELLO_BODY)
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            Words.FOR_EXAMPLE,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        HelloExamples(ui.textOnly, animate = !reduceMotion && motionAllowed(LocalContext.current))
    }
}

/** Get started, under the privacy line; the download note only when Get started downloads. */
@Composable
internal fun HelloActions(ui: SetupUi, actions: SetupActions) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OnDevice()
        Button(onClick = actions.getStarted, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(Words.GET_STARTED) }
        if (ui.downloadsOnGetStarted) {
            Text(
                Words.downloadNote(Format.downloadSize(LocalContext.current, ui.downloadBytes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The access screen: what access is for, and three tiles that picture it (not buttons). Its actions are pinned below. */
@Composable
internal fun ColumnScope.AccessStage() {
    Heading(Words.ACCESS_TITLE, Words.ANDROID_ACCESS_BODY)
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp).clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AccessTile(R.drawable.ic_image, Words.kindLabelPlural(Kind.PHOTO))
        AccessTile(R.drawable.ic_description, Words.DOCUMENTS)
        AccessTile(R.drawable.ic_download, Words.DOWNLOADS)
    }
}

/** Allow access (Continue once the whole phone is allowed), what to do if it was left off, and the quieter choice. */
@Composable
internal fun AccessActions(ui: SetupUi, allFilesOff: Boolean, actions: SetupActions) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OnDevice()
        if (ui.access.allFiles) {
            Button(onClick = actions.continueFromAccess, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(Words.CONTINUE) }
        } else {
            if (allFilesOff) Muted(Words.ALL_FILES_OFF)
            Button(onClick = actions.allowFiles, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(Words.ALLOW_ACCESS) }
            TextButton(onClick = actions.chooseInstead) { Text(Words.INCLUDE_TITLE) }
        }
    }
}

@Composable
private fun ColumnScope.Heading(title: String, body: String) {
    Text(
        title,
        style = MaterialTheme.typography.headlineLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().semantics { heading() },
    )
    Text(
        body,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RowScope.AccessTile(icon: Int, label: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.weight(1f).height(92.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The privacy line over Hello's and the access screen's button. */
@Composable
private fun OnDevice() {
    Row(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Text(Words.onDevice(DeviceWords.ANDROID), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** Choose what to include, in a sheet: media and folders, each opening its own system screen; Continue once any is given. */
@Composable
internal fun IncludeSheet(ui: SetupUi, asked: Boolean, folderError: String?, actions: SetupActions) {
    val a = ui.access
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(Words.INCLUDE_TITLE, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Column {
            AccessRow(
                R.drawable.ic_image,
                Words.PHOTOS_AND_VIDEOS,
                a.allFiles || (a.photos != Photos.NONE && a.videos),
                actions.allowMedia,
                ONLY_PICKED_PHOTOS.takeIf { !a.allFiles && a.photos == Photos.SELECTED },
            )
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

/**
 * The pile: the last [SamplePile.SHOWN] files read, each at its plan index's slot, so a card never moves once it
 * lands; with none yet, three placeholders of the plan's kinds. One node for a screen reader: the count, and See all
 * once there is a plan to see.
 */
@Composable
internal fun SamplePile(
    cards: List<SampleFile>,
    kinds: List<Kind>,
    reduceMotion: Boolean,
    label: String,
    seeAllLabel: String,
    onSeeAll: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // Only a card above the pile's top at first sight settles, so reopening the screen never replays the pile.
    val firstTop = remember { cards.maxOfOrNull { it.index } ?: -1 }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val footprint = min(SamplePile.FOOTPRINT_DP.dp, maxWidth)
        val card = footprint * SamplePile.CARD_DP / SamplePile.FOOTPRINT_DP
        // The slots shrink with the pile, as on iOS, so a narrow pile keeps its shape.
        val scale = card / SamplePile.CARD_DP.dp
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
                        modifier = Modifier.zIndex((kinds.size - i).toFloat()).graphicsLayer { slot(i, 0f, scale) }.size(card).clearAndSetSemantics {},
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
            for (c in cards) key(c.index) { PileCard(c, card, scale, animate = !reduceMotion && c.index > firstTop) }
        }
    }
}

/** A card settles from above, tilted and faded, onto its slot; with motion reduced, or already shown, it is simply there. */
@Composable
private fun PileCard(c: SampleFile, size: Dp, scale: Float, animate: Boolean) {
    val landed = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) { landed.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)) }
    FileTile(c.file, size, Modifier.zIndex(c.index.toFloat()).graphicsLayer { slot(c.index, 1f - landed.value, scale) }.testTag("card:${c.index}"))
}

/** [i]'s slot on the pile, its offsets at [scale]; [away] 1 is the settle's start: 48 dp above, 8° more turned, transparent. */
private fun GraphicsLayerScope.slot(i: Int, away: Float, scale: Float) {
    rotationZ = SamplePile.angle(i) + SETTLE_TURN * away
    translationX = (SamplePile.dx(i).dp * scale).toPx()
    translationY = (SamplePile.dy(i).dp * scale - SETTLE_DROP * away).toPx()
    alpha = (1f - away).coerceIn(0f, 1f)
}

private const val SETTLE_TURN = 8f

private val SETTLE_DROP = 48.dp

@Composable
internal fun ErrorText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}
