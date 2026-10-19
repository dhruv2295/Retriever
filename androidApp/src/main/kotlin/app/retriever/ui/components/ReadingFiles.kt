package app.retriever.ui.components

import android.animation.ValueAnimator
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.retriever.core.SourceFile
import app.retriever.ui.Format

@Composable
internal fun ReadingNow(file: SourceFile?, pauseReason: String? = null, size: Dp = 88.dp, reduceMotion: Boolean = !ValueAnimator.areAnimatorsEnabled()) {
    val sweep = if (file == null || reduceMotion) {
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
        Text(if (file == null) pauseReason ?: "Starting reading…" else "Reading now", style = MaterialTheme.typography.titleSmall)
        if (reduceMotion) {
            ReadingFile(file, pauseReason, size, sweep)
        } else {
            Crossfade(file, modifier = Modifier.testTag("reading-crossfade"), animationSpec = tween(200), label = "Reading file") { current ->
                ReadingFile(current, pauseReason, size, sweep)
            }
        }
    }
}

@Composable
private fun ReadingFile(file: SourceFile?, pauseReason: String?, size: Dp, sweep: State<Float>?) {
    if (file == null) {
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        val modifier = Modifier.clip(MaterialTheme.shapes.medium)
        Thumb(file, size, if (sweep == null) modifier else modifier.readingSweep(size, sweep))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.MiddleEllipsis)
            Text(Format.kindLabel(file.kind), style = MaterialTheme.typography.titleMedium)
            Muted(Format.date(file.modifiedAt))
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

@Composable
internal fun ReadyFiles(
    files: List<SourceFile>,
    read: Int,
    reduceMotion: Boolean = !ValueAnimator.areAnimatorsEnabled(),
    onOpen: ((SourceFile) -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Files you can try", style = MaterialTheme.typography.titleSmall)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 4,
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "Ready to search"
                collectionInfo = CollectionInfo((files.size + 3) / 4, 4)
            },
        ) {
            // Flat URI keys preserve the arrival animation when a file moves to another row.
            for (file in files) {
                key(file.uri) {
                    RecentFileCell(file, null, reduceMotion, Modifier.weight(1f).aspectRatio(1f), onOpen)
                }
            }
            repeat((4 - files.size % 4) % 4) { Spacer(Modifier.weight(1f).aspectRatio(1f)) }
        }
        val more = (read - files.size).coerceAtLeast(0)
        if (more > 0) Muted("+$more more")
    }
}

@Composable
internal fun RecentlyRead(files: List<SourceFile>, onOpen: (SourceFile) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Recently read", style = MaterialTheme.typography.titleSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics { contentDescription = "Recently read" }) {
            items(files, key = { it.uri }) { file ->
                RecentFileCell(file, 56.dp, !ValueAnimator.areAnimatorsEnabled(), onOpen = onOpen)
            }
        }
    }
}

@Composable
internal fun RecentFileCell(file: SourceFile, size: Dp?, reduceMotion: Boolean, modifier: Modifier = Modifier, onOpen: ((SourceFile) -> Unit)? = null) {
    val arrival = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) arrival.snapTo(1f) else arrival.animateTo(1f, tween(220))
    }
    Box(modifier.then(if (size != null) Modifier.size(size) else Modifier)) {
        app.retriever.files.FilePressMenu(file) { menu, actions ->
            val click = if (onOpen == null) {
                Modifier
            } else {
                actions.combinedClickable(
                    role = Role.Button,
                    onClickLabel = "Open",
                    onLongClickLabel = "File actions",
                    onLongClick = menu,
                    onClick = { onOpen(file) },
                )
            }
            val image = Modifier.fillMaxSize().graphicsLayer {
                alpha = arrival.value
                scaleX = 0.92f + 0.08f * arrival.value
                scaleY = scaleX
            }.clip(MaterialTheme.shapes.medium).then(click).semantics {
                contentDescription = "${file.name}, ${Format.kindLabel(file.kind)}, ${Format.date(file.modifiedAt)}"
            }.testTag("recent:${file.uri}")
            if (size == null) MeasuredThumbnailImage(file, image) else Thumb(file, size, image)
        }
    }
}
