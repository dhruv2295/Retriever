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

/** Selection is local; only the confirmation authorizes processing. Shared by setup and the Home sheet. */
@Composable
fun CommitStage(ui: SetupUi, actions: SetupActions, modifier: Modifier = Modifier) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ui.taste?.let { Words.demoEnded(it.done.toLong(), it.planned.toLong(), ui.setup.skipped) }?.let {
            Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(Words.chooseTitle(DeviceWords.ANDROID), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Text(Words.chooseBody(ui.rest), style = MaterialTheme.typography.bodyLarge)
        Text(Words.choosePace(DeviceWords.ANDROID), style = MaterialTheme.typography.bodyMedium)
        val labels = listOf(ReadingSchedule.CHARGING.title, Words.READ_15_NOW, Words.NOT_NOW)
        val details = listOf(Words.whileChargingBody(DeviceWords.ANDROID), Words.chargingAndNowBody(DeviceWords.ANDROID), Words.NOT_NOW_BODY)
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
        Button(onClick = {
            when (selected) {
                0 -> actions.commit(true)
                1 -> actions.commit(false)
                else -> actions.decideLater()
            }
        }, modifier = Modifier.fillMaxWidth()) {
            Text(if (selected == 2) Words.GO_TO_HOME else Words.START_PROCESSING)
        }
    }
}
