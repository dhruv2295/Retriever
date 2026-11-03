package app.retriever.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.retriever.core.DeviceWords
import app.retriever.core.ReadingSchedule
import app.retriever.core.Words

/**
 * Home's sheet for an install that chose Not now before this version: when to process the rest. Selection is local; only
 * the confirmation authorizes processing. [rest]: files still to process, null until counted.
 */
@Composable
fun CommitStage(rest: Long?, commit: (whileCharging: Boolean) -> Unit, modifier: Modifier = Modifier) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(Words.chooseTitle(DeviceWords.ANDROID), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Text(Words.chooseBody(rest), style = MaterialTheme.typography.bodyLarge)
        Text(Words.choosePace(DeviceWords.ANDROID), style = MaterialTheme.typography.bodyMedium)
        val labels = listOf(ReadingSchedule.CHARGING.title, Words.READ_15_NOW)
        val details = listOf(Words.whileChargingBody(DeviceWords.ANDROID), Words.chargingAndNowBody(DeviceWords.ANDROID))
        Column(Modifier.selectableGroup()) {
            labels.forEachIndexed { i, label ->
                ListItem(
                    headlineContent = { Text(label) },
                    // Above the title, not trailing, so the description keeps the row's full width.
                    overlineContent = if (i == 0) {
                        { Text(Words.RECOMMENDED, color = MaterialTheme.colorScheme.primary) }
                    } else {
                        null
                    },
                    supportingContent = { Text(details[i]) },
                    leadingContent = { RadioButton(selected == i, onClick = null) },
                    modifier = Modifier.selectable(selected == i, role = Role.RadioButton) { selected = i },
                )
            }
        }
        Button(onClick = { commit(selected == 0) }, modifier = Modifier.fillMaxWidth()) {
            Text(Words.START_PROCESSING)
        }
    }
}
