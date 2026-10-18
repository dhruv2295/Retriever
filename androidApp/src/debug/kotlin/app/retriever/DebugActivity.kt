package app.retriever

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.retriever.core.Backend
import app.retriever.core.DeviceProfile
import app.retriever.core.ModelBuilds
import app.retriever.core.Retriever
import app.retriever.core.Store
import app.retriever.core.Tier
import app.retriever.ui.RetrieverTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Debug builds only: demo mode and the search eval, from a second launcher entry. */
class DebugActivity : ComponentActivity() {
    /** No equals, so asking for the same run twice starts it twice. */
    class Request(val run: String, val q: String?)

    private var request by mutableStateOf<Request?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        request = requestOf(intent)
        setContent { RetrieverTheme { DebugScreen(request) } }
    }

    // `am start` hands a repeat launch to the live instance. Wait for "done <run>" in logcat before the next one.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        request = requestOf(intent)
    }

    // adb shell "am start -n app.retriever/.DebugActivity --es run corpus|scan|latency|query|stats|first-run-fast [--es q 'some text']"
    //   corpus, scan, latency, query, stats: the eval runs, see EvalRunner.
    //   first-run-fast: writes a canned CPU profile (no tune), sets the plan budget to 20 s (meta `taste_budget_s`),
    //   clears the first-run meta (taste_scope, taste_ids, taste_decided), resets setupStarted, and restarts the app
    //   into the first run. Debug source set only, so release has no such path.
    private fun requestOf(intent: Intent) = intent.getStringExtra("run")?.let { Request(it, intent.getStringExtra("q")) }
}

@Composable
private fun DebugScreen(request: DebugActivity.Request?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val demoOn = remember { Demo.isOn(context) }
    var report by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    fun launchEval(eval: suspend EvalRunner.() -> String) {
        running = true
        report = "Running…"
        scope.launch {
            report = try {
                EvalRunner().eval()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Eval failed: $e"
            } finally {
                running = false
            }
        }
    }
    LaunchedEffect(request) {
        request?.let { r ->
            if (r.run == FIRST_RUN_FAST) {
                report = "Resetting the first run…"
                FirstRunFast.restart(context)
            } else {
                launchEval { runNamed(r.run, r.q) }
            }
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Retriever debug", style = MaterialTheme.typography.headlineSmall)
            OutlinedButton(onClick = { Demo.toggle(context) }) {
                Text(if (demoOn) "Demo off (restarts the app)" else "Demo on (restarts the app)")
            }
            Button(enabled = !running, onClick = { launchEval { run() } }) { Text("Run eval") }
            Button(enabled = !running, onClick = { launchEval { runNamed("corpus", null) } }) { Text("Run corpus eval (Download/retriever-eval)") }
            Text(report, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private const val FIRST_RUN_FAST = "first-run-fast"

/** Skips the minutes-long tune and shortens the planned read, so the first run is testable by hand. */
private object FirstRunFast : KoinComponent {
    private val store: Store by inject()
    private val retriever: Retriever by inject()

    /** The plan budget the first run reads; the planner takes it from this meta key when present. */
    const val BUDGET_KEY = "taste_budget_s"
    private val FIRST_RUN_KEYS = listOf("taste_scope", "taste_ids", "taste_decided")

    suspend fun restart(context: Context) {
        withContext(Dispatchers.IO) {
            // Store has no meta delete and a set flag is any value, so the keys go by SQL; Prefs and the rest go through the app.
            SQLiteDatabase.openDatabase(context.getDatabasePath(Store.DB_NAME).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("DELETE FROM index_meta WHERE key IN (${FIRST_RUN_KEYS.joinToString { "'$it'" }})")
            }
            store.setProfile(DeviceProfile(Tier.BALANCED, ModelBuilds.variantFor(retriever.probe), Backend.CPU, 70, 5, false, false))
            store.setMeta(BUDGET_KEY, "20")
            retriever.updatePrefs { it.copy(setupStarted = false) }
        }
        context.startActivity(Intent.makeRestartActivityTask(Intent(context, MainActivity::class.java).component))
        Runtime.getRuntime().exit(0)
    }
}
