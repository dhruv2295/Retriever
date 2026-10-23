package app.retriever.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.retriever.R
import app.retriever.core.DescriptionState
import app.retriever.core.DeviceWords
import app.retriever.core.Words
import app.retriever.core.motion.MascotMood
import app.retriever.motion.Mascot

/**
 * Home's one-time card after setup: what happens next to the recent files, the rest, and new ones. Until Got it it stands
 * in for the status line, which then carries the state and its action. [size]: the download's, in the platform's format.
 * [onNotify]: null when there is nothing to ask (granted already, or below Android 13).
 */
@Composable
internal fun HomeIntroCard(
    state: DescriptionState,
    planned: Long,
    rest: Long?,
    size: String,
    onRecentAction: () -> Unit,
    onNotify: (() -> Unit)?,
    onGotIt: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.extraLarge, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 18.dp, bottom = 8.dp)) {
            Row(Modifier.padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Mascot(MascotMood.RESTING, Modifier.width(76.dp))
                Column {
                    Text(Words.HOME_INTRO_TITLE, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Text(Words.HOME_INTRO_BODY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.padding(top = 14.dp, end = 8.dp)) {
                IntroRow(R.drawable.ic_check, Words.recentFiles(planned), Words.recentFilesBody(state), filled = state == DescriptionState.READY) {
                    Words.recentFilesAction(state, size)?.let {
                        FilledTonalButton(onClick = onRecentAction, modifier = Modifier.padding(top = 4.dp)) { Text(it) }
                    }
                }
                IntroRow(R.drawable.ic_bolt, Words.otherFiles(rest), Words.otherFilesBody(DeviceWords.ANDROID))
                IntroRow(R.drawable.ic_add, Words.NEW_FILES, Words.NEW_FILES_BODY)
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                onNotify?.let { TextButton(onClick = it) { Text(Words.NOTIFY_WHEN_SEARCHABLE) } }
                TextButton(onClick = onGotIt) { Text(Words.GOT_IT) }
            }
        }
    }
}

/** One step: a small round icon ([filled] once done), the title, its line, and an optional action under them. */
@Composable
private fun IntroRow(@DrawableRes icon: Int, title: String, body: String, filled: Boolean = false, action: @Composable () -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(color = if (filled) colors.primary else colors.secondaryContainer, shape = CircleShape, modifier = Modifier.size(30.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(icon),
                    contentDescription = null,
                    tint = if (filled) colors.onPrimary else colors.onSecondaryContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            action()
        }
    }
}
