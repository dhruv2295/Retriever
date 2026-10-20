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
import app.retriever.core.Words

/** Selection is local; only the confirmation authorizes reading. Shared by setup and the Home sheet. */
@Composable
fun CommitStage(ui: SetupUi, actions: SetupActions, modifier: Modifier = Modifier) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Search inside more files", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Text(
            Words.moreFiles(ui.moreFiles),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text("A large library can take hours of reading, spread across several sessions. You don’t need to wait.", style = MaterialTheme.typography.bodyMedium)
        Text("When should Retriever read them?", style = MaterialTheme.typography.titleMedium)
        val labels = listOf("While charging", "Read now for 15 minutes", "Not now")
        val details = listOf(
            "Recommended. Reads while charging when your screen is off, or while Retriever is open.",
            "Uses battery and may warm your phone. Then continues while charging. Stops after 15 minutes, or sooner if needed.",
            "Keep searching names and the content read so far. Choose a schedule later.",
        )
        Column(Modifier.selectableGroup()) {
            labels.forEachIndexed { i, label ->
                ListItem(
                    headlineContent = { Text(label) },
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
            Text(listOf("Use this schedule", "Start reading", "Go to Home")[selected])
        }
    }
}
