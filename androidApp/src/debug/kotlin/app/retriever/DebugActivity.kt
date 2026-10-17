package app.retriever

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Debug builds only: demo mode and the search eval, from a second launcher entry. */
class DebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RetrieverTheme { DebugScreen() } }
    }
}

@Composable
private fun DebugScreen() {
    val context = LocalContext.current
    val graph = (context.applicationContext as RetrieverApp).graph
    val scope = rememberCoroutineScope()
    val demoOn = remember { Demo.isOn(context) }
    var report by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Retriever debug", style = MaterialTheme.typography.headlineSmall)
            OutlinedButton(onClick = { Demo.toggle(context) }) {
                Text(if (demoOn) "Demo off (restarts the app)" else "Demo on (restarts the app)")
            }
            fun launchEval(eval: suspend EvalRunner.() -> String) {
                running = true
                report = "Running…"
                scope.launch {
                    report = try {
                        EvalRunner(graph).eval()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        "Eval failed: $e"
                    } finally {
                        running = false
                    }
                }
            }
            Button(enabled = !running, onClick = { launchEval { run() } }) { Text("Run eval") }
            Button(enabled = !running, onClick = { launchEval { runCorpus() } }) { Text("Run corpus eval (Download/retriever-eval)") }
            Text(report, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}
