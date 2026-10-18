package app.retriever.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.retriever.ui.Format

/** The commit choice: one sentence about the rest, then three one-tap answers. No mascot: the decision is the focal point. */
@Composable
fun CommitStage(ui: SetupUi, actions: SetupActions, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Read everything?", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Text(
            Format.restSentence(ui.estimate?.files, ui.estimate),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Button(onClick = { actions.commit(true) }, modifier = Modifier.fillMaxWidth()) { Text("Read while charging") }
            FilledTonalButton(onClick = { actions.commit(false) }, modifier = Modifier.fillMaxWidth()) { Text("Read now") }
            Text("Uses noticeably more battery today.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = actions.decideLater) { Text("Not yet") }
        }
    }
}
