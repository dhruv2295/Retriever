package app.retriever.setup

import android.animation.ValueAnimator
import android.os.PowerManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.retriever.R
import app.retriever.motion.motionAllowed
import kotlinx.coroutines.delay

@Composable
internal fun WelcomeExamples(textOnly: Boolean) {
    val power = LocalContext.current.getSystemService(PowerManager::class.java)
    val animate = motionAllowed(ValueAnimator.areAnimatorsEnabled(), power.isPowerSaveMode, power.currentThermalStatus)
    val queries = if (textOnly) EXAMPLE_QUERIES.drop(1) else EXAMPLE_QUERIES
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            queries.forEachIndexed { index, query ->
                key(query) {
                    ExampleQuery(query, 400L + queries.take(index).sumOf { it.length * 35L + 250L }, animate)
                }
            }
        }
    }
}

private val EXAMPLE_QUERIES = listOf("“sunset at the beach”", "“the Wi-Fi password screenshot”", "“lease that mentions the deposit”")

/** Only this row observes each character; restoring the screen shows the completed examples. */
@Composable
private fun ExampleQuery(query: String, startDelay: Long, animate: Boolean) {
    var played by rememberSaveable { mutableStateOf(false) }
    var typed by remember { mutableIntStateOf(if (played || !animate) query.length else 0) }
    LaunchedEffect(animate) {
        val shouldPlay = !played && animate
        played = true
        if (shouldPlay) {
            delay(startDelay)
            for (count in 1..query.length) {
                typed = count
                delay(35)
            }
        } else {
            typed = query.length
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(R.drawable.ic_search), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        // The full string remains in both layout and text semantics, including the transparent tail.
        Text(
            buildAnnotatedString {
                append(query.take(typed))
                if (typed < query.length) withStyle(SpanStyle(color = Color.Transparent)) { append(query.drop(typed)) }
            },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
    }
}
