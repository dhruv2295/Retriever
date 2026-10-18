package app.retriever.ui.components

import android.animation.ValueAnimator
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
        Text("Reading now", style = MaterialTheme.typography.titleSmall)
        if (reduceMotion) {
            ReadingFile(file, pauseReason, size, sweep)
        } else {
            Crossfade(file, animationSpec = tween(200), label = "Reading file") { current ->
                ReadingFile(current, pauseReason, size, sweep)
            }
        }
    }
}

@Composable
private fun ReadingFile(file: SourceFile?, pauseReason: String?, size: Dp, sweep: State<Float>?) {
    if (file == null) {
        Muted(pauseReason ?: "No file is being read right now.")
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        val modifier = Modifier.clip(MaterialTheme.shapes.medium)
        Thumb(file, size, if (sweep == null) modifier else modifier.readingSweep(size, sweep))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
internal fun ReadyFiles(files: List<SourceFile>, read: Int, reduceMotion: Boolean = !ValueAnimator.areAnimatorsEnabled()) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Ready to search", style = MaterialTheme.typography.titleSmall)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val size = (maxWidth - 24.dp) / 4
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 4,
                modifier = Modifier.semantics {
                    contentDescription = "Ready to search"
                    collectionInfo = CollectionInfo((files.size + 3) / 4, 4)
                },
            ) {
                // Flat keys keep cells alive even when an insertion moves them to another row.
                for (file in files) key(file.uri) { RecentFileCell(file, size, reduceMotion) }
            }
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
                RecentFileCell(file, 56.dp, !ValueAnimator.areAnimatorsEnabled(), onOpen)
            }
        }
    }
}

@Composable
internal fun RecentFileCell(file: SourceFile, size: Dp, reduceMotion: Boolean, onOpen: ((SourceFile) -> Unit)? = null) {
    val arrival = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) arrival.snapTo(1f) else arrival.animateTo(1f, tween(220))
    }
    val click = if (onOpen == null) Modifier else Modifier.clickable(role = Role.Button, onClickLabel = "Open") { onOpen(file) }
    Thumb(
        file,
        size,
        Modifier.size(size).graphicsLayer {
            alpha = arrival.value
            scaleX = 0.92f + 0.08f * arrival.value
            scaleY = scaleX
        }.clip(MaterialTheme.shapes.medium).then(click).semantics {
            contentDescription = "${Format.kindLabel(file.kind)}, ${Format.date(file.modifiedAt)}"
        }.testTag("recent:${file.uri}"),
    )
}
