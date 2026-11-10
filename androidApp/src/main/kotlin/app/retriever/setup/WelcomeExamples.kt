package app.retriever.setup

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.retriever.R
import app.retriever.core.Words
import kotlinx.coroutines.delay
import kotlin.time.TimeSource

/**
 * Hello's looping example: a query types into a search pill, then the card that answers it springs in; each holds for
 * [EXAMPLE_MS]. Illustrations, never the person's files. Without motion the first example simply shows.
 */
@Composable
internal fun HelloExamples(textOnly: Boolean, animate: Boolean) {
    val examples = Words.helloExamples(textOnly)
    var index by remember { mutableIntStateOf(0) }
    var typed by remember { mutableIntStateOf(if (animate) 0 else examples[0].query.length) }
    val card = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(animate) {
        if (!animate) {
            index = 0
            typed = examples[0].query.length
            card.snapTo(1f)
            return@LaunchedEffect
        }
        while (true) {
            for ((i, e) in examples.withIndex()) {
                val start = TimeSource.Monotonic.markNow()
                index = i
                card.snapTo(0f)
                for (n in 1..e.query.length) {
                    typed = n
                    delay(TYPING_MS / e.query.length)
                }
                card.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow))
                delay((EXAMPLE_MS - FADE_MS - start.elapsedNow().inWholeMilliseconds).coerceAtLeast(0))
                card.animateTo(0f, tween(FADE_MS.toInt()))
                typed = 0
            }
        }
    }
    val e = examples[index]
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = CircleShape, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(R.drawable.ic_search), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                // The whole query is laid out from the first letter, its tail transparent, so the pill never reflows.
                Text(
                    buildAnnotatedString {
                        append(e.query.take(typed))
                        if (typed < e.query.length) withStyle(SpanStyle(color = Color.Transparent)) { append(e.query.drop(typed)) }
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ExampleCard(
            EXAMPLE_ART[Words.HELLO_EXAMPLES.indexOf(e).coerceAtLeast(0)],
            e,
            Modifier.graphicsLayer {
                val v = card.value
                alpha = v.coerceIn(0f, 1f)
                translationY = (1f - v) * 14.dp.toPx()
                scaleX = 0.97f + 0.03f * v
                scaleY = scaleX
            },
        )
    }
}

@Composable
private fun ExampleCard(@DrawableRes art: Int, e: Words.HelloExample, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Image(
                painterResource(art),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)),
            )
            Column {
                Text(e.title, style = MaterialTheme.typography.titleSmall)
                Text(e.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** By place in [Words.HELLO_EXAMPLES], which every language keeps in the same order; never by the words, which change with it. */
private val EXAMPLE_ART = listOf(R.drawable.example_chat, R.drawable.example_sofa, R.drawable.example_lease)

internal const val EXAMPLE_MS = 3_500L
private const val TYPING_MS = 1_150L
private const val FADE_MS = 200L
