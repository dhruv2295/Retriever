package app.retriever.setup

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.retriever.R
import app.retriever.access.Access
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

/** Get started, under the privacy line and, only when Get started downloads, the download line. */
@Composable
internal fun HelloActions(ui: SetupUi, actions: SetupActions) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoLine(R.drawable.ic_lock, Words.onDevice(DeviceWords.ANDROID), MaterialTheme.colorScheme.primary)
            if (ui.downloadsOnGetStarted) {
                InfoLine(
                    R.drawable.ic_download,
                    Words.downloadLine(Format.downloadSize(LocalContext.current, ui.downloadBytes)),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(onClick = actions.getStarted, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(Words.GET_STARTED) }
    }
}

/**
 * The access screen: what access is for, and three tiles that picture what was given (not buttons). Short of all files, a
 * card offers the whole phone. Its actions are pinned below.
 */
@Composable
internal fun ColumnScope.AccessStage(access: Access, allowFiles: () -> Unit = {}) {
    val all = access.allFiles
    val folders = access.folders.size.toLong()
    Heading(Words.ACCESS_TITLE, Words.ANDROID_ACCESS_BODY)
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AccessTile(R.drawable.ic_image, Words.kindLabelPlural(Kind.PHOTO), all || access.photos != Photos.NONE || access.videos)
        AccessTile(R.drawable.ic_description, Words.DOCUMENTS, all || folders > 0, Words.foldersAdded(folders).takeIf { !all && folders > 0 })
        AccessTile(R.drawable.ic_download, Words.DOWNLOADS, all)
    }
    if (access.ready) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(Words.ACCESS_SCANNING, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        }
        if (!all) WholeDeviceCard(allowFiles)
    }
}

/** Some access but not all files: what the whole phone adds, and the way to allow it. */
@Composable
private fun WholeDeviceCard(allowFiles: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                Words.strings.wholeDeviceTitle(DeviceWords.ANDROID),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(Words.strings.wholeDeviceBody, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = allowFiles, modifier = Modifier.padding(top = 6.dp)) { Text(Words.strings.allowAllFiles) }
        }
    }
}

/** Allow access (Start scanning once a source is allowed), what to do if it was left off, and the quieter choice. */
@Composable
internal fun AccessActions(ui: SetupUi, allFilesOff: Boolean, actions: SetupActions) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OnDevice()
        if (ui.access.ready) {
            Button(onClick = actions.continueFromAccess, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(Words.START_SCANNING) }
            if (!ui.access.allFiles) TextButton(onClick = actions.chooseInstead) { Text(Words.strings.changeWhatsIncluded) }
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

/** A picture of one source; ticked once given, and only then read aloud, as "Documents, 2 folders added, Allowed". */
@Composable
private fun RowScope.AccessTile(icon: Int, label: String, granted: Boolean, note: String? = null) {
    Surface(
        color = if (granted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.weight(1f).heightIn(min = 92.dp)
            .then(if (granted) Modifier.semantics(mergeDescendants = true) {} else Modifier.clearAndSetSemantics {}),
    ) {
        Box {
            Column(
                Modifier.fillMaxWidth().heightIn(min = 92.dp).padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    note?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
            }
            if (granted) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(20.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_check),
                        contentDescription = Words.ALLOWED,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

/** The privacy line over Hello's and the access screen's button. */
@Composable
private fun OnDevice() {
    InfoLine(R.drawable.ic_lock, Words.onDevice(DeviceWords.ANDROID), MaterialTheme.colorScheme.primary, Modifier.padding(bottom = 4.dp))
}

@Composable
private fun InfoLine(icon: Int, text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** Choose what to include, in a sheet: media and folders, each opening its own system screen; Continue once any is given. */
@Composable
internal fun IncludeSheet(ui: SetupUi, asked: Boolean, folderError: String?, actions: SetupActions) {
    val a = ui.access
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(Words.INCLUDE_TITLE, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        if (!a.allFiles) {
            AllFilesChoice(actions.allowFiles)
            Text(
                Words.strings.orOnly,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        Column {
            AccessRow(
                R.drawable.ic_image,
                Words.PHOTOS_AND_VIDEOS,
                a.allFiles || (a.photos != Photos.NONE && a.videos),
                actions.allowMedia,
                ONLY_PICKED_PHOTOS.takeIf { !a.allFiles && a.photos == Photos.SELECTED },
            )
            AccessRow(kindIcon(Kind.AUDIO), Words.kindLabelPlural(Kind.AUDIO), a.allFiles || a.audio, actions.allowAudio)
            AccessRow(
                R.drawable.ic_folder_open,
                Words.ADD_FOLDER,
                false,
                actions.addFolder,
                Words.strings.folderNames(a.folders.map { it.name }).ifEmpty { null },
            )
        }
        if (asked && !a.ready) Muted(Words.NOTHING_SELECTED)
        folderError?.let { ErrorText(it) }
        if (a.ready) Text(Words.ACCESS_SCANNING, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = actions.continueFromAccess, enabled = a.ready, modifier = Modifier.fillMaxWidth()) { Text(Words.START_SCANNING) }
    }
}

/** The whole phone, first and marked as the better choice; it opens Settings' all-files switch. */
@Composable
private fun AllFilesChoice(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    ListItem(
        headlineContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Words.strings.allFilesChoice)
                Surface(color = colors.primary, contentColor = colors.onPrimary, shape = CircleShape) {
                    Text(
                        Words.strings.recommended,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        },
        supportingContent = { Text(Words.strings.allFilesChoiceBody) },
        leadingContent = { Icon(painterResource(R.drawable.ic_storage), contentDescription = null) },
        trailingContent = { Icon(painterResource(R.drawable.ic_keyboard_arrow_right), contentDescription = null) },
        colors = ListItemDefaults.colors(
            containerColor = colors.secondaryContainer,
            headlineColor = colors.onSecondaryContainer,
            supportingColor = colors.onSecondaryContainer,
            leadingIconColor = colors.onSecondaryContainer,
            trailingIconColor = colors.onSecondaryContainer,
        ),
        modifier = Modifier.clip(MaterialTheme.shapes.large).clickable(role = Role.Button, onClick = onClick),
    )
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
                contentDescription = if (given) Words.ALLOWED else null,
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
    Box(modifier, contentAlignment = Alignment.Center) {
        // The cards are a fixed share of the pile, and their slots shrink with them (as on iOS), so a narrow pile keeps
        // its shape.
        val card = Modifier.fillMaxSize(SamplePile.CARD_DP.toFloat() / SamplePile.FOOTPRINT_DP)
        Box(
            Modifier.widthIn(max = SamplePile.FOOTPRINT_DP.dp).fillMaxWidth().aspectRatio(1f)
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
                        modifier = Modifier.zIndex((kinds.size - i).toFloat()).graphicsLayer { slot(i, 0f) }.then(card).clearAndSetSemantics {},
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
            for (c in cards) key(c.index) { PileCard(c, animate = !reduceMotion && c.index > firstTop, card) }
        }
    }
}

/** A card settles from above, tilted and faded, onto its slot; with motion reduced, or already shown, it is simply there. */
@Composable
private fun PileCard(c: SampleFile, animate: Boolean, modifier: Modifier = Modifier) {
    val landed = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) { landed.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)) }
    // Asks for a full card's picture; [modifier] sizes it to the pile.
    val layer = Modifier.zIndex(c.index.toFloat()).graphicsLayer { slot(c.index, 1f - landed.value) }
    FileTile(c.file, SamplePile.CARD_DP.dp, layer.testTag("card:${c.index}").then(modifier))
}

/**
 * [i]'s slot on the pile, its offsets scaled as this card is to a full one; [away] 1 is the settle's start: 48 dp above,
 * 8° more turned, transparent.
 */
private fun GraphicsLayerScope.slot(i: Int, away: Float) {
    val scale = size.width / SamplePile.CARD_DP.dp.toPx()
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
