package app.retriever.ui.components

import android.animation.ValueAnimator
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.retriever.core.SourceFile
import app.retriever.core.Words
import app.retriever.ui.Format

/**
 * The file being read, held in place: once core reports none (a batch ended) the last one stays, so the row never
 * collapses or flips its label; its text changes in place, and the old picture stays until the new one is decoded, then
 * gives way in a short fade (none with motion reduced). Before any file, the row keeps its height, empty.
 */
@Composable
internal fun ReadingNow(file: SourceFile?, size: Dp = 88.dp, reduceMotion: Boolean = !ValueAnimator.areAnimatorsEnabled()) {
    // A plain holder, not state: a new file recomposes this anyway, and a null one falls back to the last.
    val held = remember {
        object {
            var file: SourceFile? = null
        }
    }
    if (file != null) held.file = file
    val shown = held.file
    val sweep = if (shown == null || reduceMotion) {
        null
    } else {
        rememberInfiniteTransition(label = "Reading sweep").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
            label = "Reading sweep position",
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (shown == null) Words.STARTING else Words.PROCESSING_NOW, style = MaterialTheme.typography.titleSmall)
        ReadingFile(shown, size, sweep, reduceMotion)
    }
}

@Composable
private fun ReadingFile(file: SourceFile?, size: Dp, sweep: State<Float>?, reduceMotion: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        val modifier = Modifier.size(size).clip(MaterialTheme.shapes.medium)
        if (file == null) {
            Box(modifier.background(MaterialTheme.colorScheme.surfaceContainer))
        } else {
            ReadingThumb(file, size, reduceMotion, if (sweep == null) modifier else modifier.readingSweep(size, sweep))
        }
        // Empty lines hold the same height as a file's, so the first file lands without moving anything.
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(file?.name.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(file?.let { Words.kindLabel(it.kind) }.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Muted(file?.let { Format.date(it.modifiedAt) }.orEmpty(), maxLines = 1)
        }
    }
}

/** [file]'s picture, the previous file's held until this one's is decoded; then an image-only fade. */
@Composable
private fun ReadingThumb(file: SourceFile, size: Dp, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val px = Thumbnails.bucket(with(LocalDensity.current) { size.roundToPx() })
    // produceState's value outlives its keys: the old pair stays until the new file's load returns.
    val loaded by produceState(file to Thumbnails.cached(context, file, px), file.uri, file.modifiedAt, file.size, px) {
        value = file to Thumbnails.load(context, file, px)
    }
    Crossfade(loaded, modifier, animationSpec = if (reduceMotion) snap() else tween(150), label = "Reading picture") { (f, bitmap) ->
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) else KindFace(f)
        }
    }
}

/** The brush and animation are retained; only drawing reads the clock. */
@Composable
private fun Modifier.readingSweep(size: Dp, position: State<Float>): Modifier {
    val height = with(LocalDensity.current) { size.toPx() }
    val band = height * 0.45f
    val brush = remember(band) { Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.28f), Color.Transparent), endY = band) }
    return testTag("reading-sweep").drawWithContent {
        drawContent()
        translate(top = position.value * (height + band) - band) { drawRect(brush) }
    }
}

/**
 * Recently read, held still: a strip that moved with every file read (twice a second) could be neither read nor tapped.
 * It fills in reading order, so a tile keeps its place as the next one lands at the end, then holds while [running] and
 * catches up when reading stops. [files] is core's, newest first.
 */
@Composable
internal fun RecentlyRead(files: List<SourceFile>, running: Boolean, onOpen: (SourceFile) -> Unit) {
    // A plain holder, not state: a new list recomposes this anyway. The rows read [shown], not the holder, so the row's
    // content lambda changes exactly when the held list does (one capturing only the holder would be skipped).
    val held = remember {
        object {
            var files = emptyList<SourceFile>()
        }
    }
    if (!running || held.files.size < RECENT_SHOWN) held.files = files.take(RECENT_SHOWN).asReversed()
    val shown = held.files
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(Words.RECENTLY_PROCESSED, style = MaterialTheme.typography.titleSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics { contentDescription = Words.RECENTLY_PROCESSED }) {
            items(shown, key = { it.uri }) { file -> FileTile(file, 56.dp, onOpen = onOpen) }
        }
    }
}

/** Tiles in Recently read: about a phone's width. */
private const val RECENT_SHOWN = 6

/**
 * One file's preview at a fixed [size], the same everywhere it shows: the demo's pile, Recently read.
 * With [onOpen] it opens on tap, offers the file menu on long press and says "name, kind, date"; without, it is
 * decoration and says nothing. A photo opened from it grows from it in [morph].
 */
@Composable
internal fun FileTile(file: SourceFile, size: Dp, modifier: Modifier = Modifier, morph: PhotoMorph? = null, onOpen: ((SourceFile) -> Unit)? = null) {
    val image = Modifier.size(size).clip(MaterialTheme.shapes.medium)
    if (onOpen == null) {
        ThumbnailImage(file, size, modifier.then(image).clearAndSetSemantics {})
        return
    }
    app.retriever.files.FilePressMenu(file, modifier) { menu, actions ->
        val click = actions.combinedClickable(
            role = Role.Button,
            onClickLabel = "Open",
            onLongClickLabel = "File actions",
            onLongClick = menu,
            onClick = { onOpen(file) },
        )
        ThumbnailImage(
            file,
            size,
            image.then(click).semantics { contentDescription = "${file.name}, ${Words.kindLabel(file.kind)}, ${Format.date(file.modifiedAt)}" }
                .testTag("recent:${file.uri}"),
            morph = morph,
        )
    }
}
