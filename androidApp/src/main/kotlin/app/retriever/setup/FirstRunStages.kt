package app.retriever.setup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.retriever.R
import app.retriever.access.Photos
import app.retriever.core.DeviceWords
import app.retriever.core.IndexState
import app.retriever.core.Kind
import app.retriever.core.SourceFile
import app.retriever.core.TasteProgress
import app.retriever.core.Words
import app.retriever.ui.components.MeasuredThumbnailImage
import app.retriever.ui.components.Muted
import app.retriever.ui.components.kindIcon
import kotlinx.coroutines.delay

@Composable
internal fun ColumnScope.Welcome(ui: SetupUi, actions: SetupActions) {
    Text(Words.WELCOME_TITLE, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    Text(Words.welcomeBody(DeviceWords.ANDROID), style = MaterialTheme.typography.bodyLarge)
    WelcomeExamples(ui.textOnly)
    Button(onClick = actions.getStarted, modifier = Modifier.fillMaxWidth()) { Text(Words.GET_STARTED) }
    TextButton(onClick = actions.searchByName, modifier = Modifier.fillMaxWidth()) { Text(Words.SEARCH_BY_NAME) }
    if (ui.textOnly) Muted(Words.textOnlyNote(ui.ramGb, DeviceWords.ANDROID))
}

/** One row per kind of access, each opening its own system screen; Continue once any is given. */
@Composable
internal fun ColumnScope.AccessStage(ui: SetupUi, filesDenied: Boolean, folderError: String?, actions: SetupActions) {
    val a = ui.access
    Text(Words.CHOOSE_ACCESS, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    Text(Words.ACCESS_BODY, style = MaterialTheme.typography.bodyMedium)
    Column {
        AccessRow(R.drawable.ic_storage, Words.FILES_AND_FOLDERS, a.allFiles, actions.allowFiles)
        AccessRow(R.drawable.ic_image, Words.PHOTOS_AND_VIDEOS, a.allFiles || (a.photos != Photos.NONE && a.videos), actions.allowMedia)
        AccessRow(kindIcon(Kind.AUDIO), Words.kindLabelPlural(Kind.AUDIO), a.allFiles || a.audio, actions.allowMedia)
        AccessRow(R.drawable.ic_folder_open, Words.ADD_FOLDER, false, actions.addFolder, a.folders.joinToString(" · ") { it.name }.ifEmpty { null })
    }
    if (filesDenied && !a.ready) Muted(Words.NOTHING_SELECTED)
    folderError?.let { ErrorText(it) }
    Button(onClick = actions.continueFromAccess, enabled = a.ready, modifier = Modifier.fillMaxWidth()) { Text(Words.CONTINUE) }
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
        modifier = Modifier.clickable(role = Role.Button, onClickLabel = if (label == Words.ADD_FOLDER) label else Words.CHOOSE_FILE_ACCESS, onClick = onClick),
    )
}

/**
 * The sample: what has been read, as real previews, with the file being read first. A bounded FlowRow, not a lazy
 * grid: it sits in setup's scrolling column, and six tiles need no laziness.
 */
@Composable
internal fun ColumnScope.SampleStage(ui: SetupUi, taste: TasteProgress, index: () -> IndexState, actions: SetupActions) {
    Text(Words.SAMPLE_TITLE, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    val spoken = (listOf(Words.readCount(taste.read.toLong())) + taste.byKind.map { (k, n) -> Words.kindRead(n.first.toLong(), n.second.toLong(), k) })
        .joinToString(", ")
    Text(Words.readCount(taste.read.toLong()), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { contentDescription = spoken })
    val s = index()
    val done = taste.byKind.isNotEmpty() && taste.readyKinds.size == taste.byKind.size
    val reading = s.running && ui.pauseReason == null && !done
    SampleTime(reading, ui.sampleStartedAt)
    if (!done) ui.pauseReason?.let { Muted(it) }
    val current = s.currentFile.takeIf { !done }
    val read = s.recentFiles.filter { it.uri != current?.uri }
    if (read.isEmpty()) Muted(Words.SAMPLE_STARTING)
    // Six at most, and whole rows, so no slot is ever left blank.
    val tiles = (listOfNotNull(current) + read).take(SAMPLE_TILES).let { if (it.size > 1) it.take(it.size / 2 * 2) else it }
    val oneColumn = LocalDensity.current.fontScale > LARGE_TEXT
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = if (oneColumn) 1 else 2,
    ) {
        for (f in tiles) {
            androidx.compose.runtime.key(f.uri) { SampleTile(f, reading = f.uri == current?.uri, row = oneColumn, Modifier.weight(1f)) }
        }
    }
    if (taste.read > tiles.size) TextButton(onClick = actions.seeAll) { Text(Words.seeAll(taste.read.toLong())) }
}

/** Core's time line while the sample reads, which turns to "taking longer" once the usual time has passed. */
@Composable
private fun SampleTime(reading: Boolean, startedAt: Long) {
    // Only this line redraws as time passes, and only while the sample reads.
    val elapsed by produceState(0L, reading, startedAt) {
        while (reading && startedAt > 0) {
            value = System.currentTimeMillis() - startedAt
            delay(TIME_TICK_MS)
        }
    }
    Words.sampleTime(reading, elapsed, DeviceWords.ANDROID)?.let { Muted(it) }
}

/** A real preview with the file's name and type: a picture, a PDF's first page, or a document's own text. */
@Composable
private fun SampleTile(file: SourceFile, reading: Boolean, row: Boolean, modifier: Modifier = Modifier) {
    val kind = Words.kindLabel(file.kind)
    val label = listOfNotNull(Words.READING_NOW.takeIf { reading }, file.name, kind).joinToString(", ")
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription =
                label
        },
    ) {
        val preview = @Composable { m: Modifier ->
            Box(m.clip(MaterialTheme.shapes.small)) {
                MeasuredThumbnailImage(file, Modifier.fillMaxSize())
                if (reading) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                    ) { Text(Words.READING_NOW, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) }
                }
            }
        }
        val name = @Composable {
            Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(kind, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (row) {
            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                preview(Modifier.size(72.dp))
                Column(Modifier.weight(1f)) { name() }
            }
        } else {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                preview(Modifier.fillMaxWidth().aspectRatio(1f))
                name()
            }
        }
    }
}

/** The sample's footer: Try search once enough is read, and the way to Home throughout. */
@Composable
internal fun SampleFooter(canTry: Boolean, actions: SetupActions) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (canTry) Button(onClick = actions.tryNow, modifier = Modifier.fillMaxWidth()) { Text(Words.TRY_SEARCH) }
        TextButton(onClick = actions.skip, modifier = Modifier.fillMaxWidth()) { Text(Words.GO_TO_HOME) }
    }
}

private const val SAMPLE_TILES = 6

private const val TIME_TICK_MS = 15_000L

/** Font scale past which tiles become one-column rows. */
private const val LARGE_TEXT = 1.3f

@Composable
internal fun ErrorText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}
