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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.retriever.core.Kind
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
        Text(if (shown == null) "Starting reading…" else "Reading now", style = MaterialTheme.typography.titleSmall)
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
    val px = with(LocalDensity.current) { size.roundToPx() }
    // produceState's value outlives its keys: the old pair stays until the new file's load returns.
    val loaded by produceState(file to Thumbnails.cached(file, px), file.uri, file.modifiedAt, file.size, px) {
        value = file to Thumbnails.load(context, file, px)
    }
    Crossfade(loaded, modifier, animationSpec = if (reduceMotion) snap() else tween(150), label = "Reading picture") { (f, bitmap) ->
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
            when {
                bitmap != null -> Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                f.kind == Kind.DOC || f.kind == Kind.OTHER -> TextThumbnail(f)
                else -> Icon(painterResource(kindIcon(f.kind)), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
 * Files you can try: one tile size for every cell, so a document's text tile never widens its row. Four columns, two
 * past [LARGE_TEXT]; a plain FlowRow of fixed sizes, no weights, so nothing asks a tile for its intrinsics.
 */
@Composable
internal fun ReadyFiles(files: List<SourceFile>, onOpen: ((SourceFile) -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Files you can try", style = MaterialTheme.typography.titleSmall)
        val columns = if (LocalDensity.current.fontScale > LARGE_TEXT) 2 else 4
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Whole pixels, rounded down, so a full row never overflows the width by a rounding pixel.
            val cell = with(LocalDensity.current) { ((constraints.maxWidth - GAP.roundToPx() * (columns - 1)) / columns).toDp() }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(GAP),
                verticalArrangement = Arrangement.spacedBy(GAP),
                maxItemsInEachRow = columns,
                modifier = Modifier.semantics {
                    contentDescription = "Ready to search"
                    collectionInfo = CollectionInfo((files.size + columns - 1) / columns, columns)
                },
            ) {
                for (file in files) key(file.uri) { FileTile(file, cell, onOpen = onOpen) }
            }
        }
    }
}

@Composable
internal fun RecentlyRead(files: List<SourceFile>, onOpen: (SourceFile) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Recently read", style = MaterialTheme.typography.titleSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics { contentDescription = "Recently read" }) {
            items(files, key = { it.uri }) { file -> FileTile(file, 56.dp, onOpen = onOpen) }
        }
    }
}

/**
 * One file's preview at a fixed [size], the same everywhere it shows: the sample's pile, Try's grid, Recently read.
 * With [onOpen] it opens on tap, offers the file menu on long press and says "name, kind, date"; without, it is
 * decoration and says nothing.
 */
@Composable
internal fun FileTile(file: SourceFile, size: Dp, modifier: Modifier = Modifier, onOpen: ((SourceFile) -> Unit)? = null) {
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
        )
    }
}

private val GAP = 8.dp

/** Font scale past which Try's grid has two columns. */
private const val LARGE_TEXT = 1.3f
