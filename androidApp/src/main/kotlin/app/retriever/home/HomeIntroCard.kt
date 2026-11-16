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
import androidx.compose.material3.OutlinedButton
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
import app.retriever.core.ReadingSchedule
import app.retriever.core.Stage
import app.retriever.core.Words
import app.retriever.core.motion.MascotMood
import app.retriever.model.FreeUpSpaceButton
import app.retriever.motion.Mascot

/**
 * Home's one-time card after setup: what happens next to the recent files, the rest, and new ones. Until Got it it stands
 * in for the status line, which then carries the state and its action. [size]: the download's, in the platform's format.
 * [onRecentAction]: the action under the recent files, and Try again after a download refused for space. [onNotify]: null once
 * the notice was asked for.
 */
@Composable
internal fun HomeIntroCard(
    state: DescriptionState,
    planned: Long,
    rest: Long?,
    size: String,
    schedule: ReadingSchedule,
    sessionActive: Boolean,
    onRecentAction: () -> Unit,
    onNotify: (() -> Unit)?,
    onGotIt: () -> Unit,
    modifier: Modifier = Modifier,
    /** People's Turn on; null hides the row (already on, or no photos to look at). */
    onTurnOnPeople: (() -> Unit)? = null,
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
                    if (state == DescriptionState.NO_SPACE) {
                        FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = onRecentAction) { Text(Words.TRY_AGAIN) }
                            FreeUpSpaceButton()
                        }
                    }
                }
                IntroRow(
                    R.drawable.ic_bolt,
                    Words.otherFiles(rest),
                    Words.processingPlanDetail(schedule, sessionActive),
                )
                IntroRow(R.drawable.ic_add, Words.NEW_FILES, Words.NEW_FILES_BODY)
                // The one choice in the card, raised off it so it reads as something to do, not a step that happens.
                onTurnOnPeople?.let { turnOn ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.padding(vertical = 4.dp),
                    ) {
                        Box(Modifier.padding(horizontal = 12.dp)) {
                            IntroRow(R.drawable.ic_people, Words.PEOPLE, Words.strings.peopleRowBody(DeviceWords.ANDROID)) {
                                OutlinedButton(onClick = turnOn, modifier = Modifier.padding(top = 8.dp)) { Text(Words.TURN_ON) }
                            }
                        }
                    }
                }
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                onNotify?.let { TextButton(onClick = it) { Text(Words.NOTIFY_WHEN_SEARCHABLE) } }
                TextButton(onClick = onGotIt) { Text(Words.GOT_IT) }
            }
        }
    }
}

/** A stage just finished: what now works and one thing to try, with the dog's one bounce, until Got it. */
@Composable
internal fun StageNoteCard(stage: Stage, textOnly: Boolean, onGotIt: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.extraLarge, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 18.dp, bottom = 8.dp)) {
            Row(Modifier.padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Mascot(MascotMood.CAUGHT_UP, Modifier.width(76.dp))
                Column {
                    Text(Words.strings.stageNoteTitle(stage), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Text(
                        Words.strings.stageNote(stage, textOnly),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onGotIt, modifier = Modifier.align(Alignment.End)) { Text(Words.GOT_IT) }
        }
    }
}

/**
 * One step: a small round icon ([filled] once done, quiet while [later]), the title, its line, and an optional action under
 * them. Also Processing's stage checklist, where [modifier] carries the row's merged semantics.
 */
@Composable
internal fun IntroRow(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    later: Boolean = false,
    action: @Composable () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val container = when {
            filled -> colors.primary
            later -> colors.surfaceContainerHighest
            else -> colors.secondaryContainer
        }
        Surface(color = container, shape = CircleShape, modifier = Modifier.size(30.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(icon),
                    contentDescription = null,
                    tint = when {
                        filled -> colors.onPrimary
                        later -> colors.onSurfaceVariant
                        else -> colors.onSecondaryContainer
                    },
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (later) colors.onSurfaceVariant else colors.onSurface)
            Text(body, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            action()
        }
    }
}
