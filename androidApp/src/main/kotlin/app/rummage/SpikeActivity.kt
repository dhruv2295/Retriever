package app.rummage

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.rummage.core.SearchEngine
import kotlinx.coroutines.launch

/** Outlives configuration changes, so a rotation or theme switch doesn't cancel a long index run. */
class SpikeViewModel(app: Application) : AndroidViewModel(app) {
    val runner = SpikeRunner(app, viewModelScope)

    // releaseSearch() can wait for a model load or an embed in flight; never on the main thread.
    override fun onCleared() {
        getApplication<RummageApp>().graph.scope.launch { runner.releaseSearch() }
    }
}

class SpikeActivity : ComponentActivity() {
    private val vm: SpikeViewModel by viewModels()

    // Bumped on resume so permission and model status recompute after returning from Settings.
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val runner = vm.runner
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                SpikeScreen(runner, resumeTick)
            }
        }
    }

    // Product indexing stays off while the spike is on screen (its own model is loaded).
    override fun onStart() {
        super.onStart()
        (application as RummageApp).graph.spikeActive = true
    }

    override fun onStop() {
        super.onStop()
        (application as RummageApp).graph.spikeActive = false
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SpikeScreen(runner: SpikeRunner, resumeTick: Int) {
    val context = LocalContext.current
    val hasAccess = remember(resumeTick) { runner.hasAllFilesAccess() }
    val models = remember(resumeTick) { runner.modelsPresent() }
    val busy = runner.busy
    val idle = busy == null
    var query by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(SearchEngine.Mode.MERGED) }

    Surface(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (hasAccess) "All files access: granted" else "All files access: missing",
                                modifier = Modifier.weight(1f),
                            )
                            if (!hasAccess) {
                                Button(onClick = {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                            Uri.parse("package:" + context.packageName),
                                        ),
                                    )
                                }) { Text("Grant") }
                            }
                        }
                        models.forEach { (variant, present) ->
                            Text("${variant.name}: ${if (present) "present" else "missing"}")
                        }
                        Text("Model folder: " + runner.modelDir.absolutePath)
                        Text("Profile: " + (runner.profile?.toString() ?: "not calibrated"))
                        if (busy != null) {
                            Text("Running: $busy")
                            Text(runner.progress)
                            OutlinedButton(onClick = { runner.cancel() }) { Text("Cancel") }
                        }
                    }
                }
            }
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(onClick = { runner.calibrate() }, enabled = idle) { Text("1 · Calibrate") }
                    FilledTonalButton(onClick = { runner.buildTestSet() }, enabled = idle) { Text("2 · Build test set") }
                    FilledTonalButton(onClick = { runner.index() }, enabled = idle) { Text("3 · Index") }
                    FilledTonalButton(onClick = { runner.eval() }, enabled = idle) { Text("Eval") }
                    FilledTonalButton(onClick = { runner.latencyTest() }, enabled = idle) { Text("Latency test") }
                }
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { runner.search(query, mode) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SearchEngine.Mode.entries.forEach { m ->
                        FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.name) })
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { runner.search(query, mode) }) { Text("Search") }
                    runner.lastQueryMs?.let {
                        Text("%.0f ms".format(it), modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
            items(runner.hits, key = { it.chunkId }) { hit ->
                ListItem(
                    headlineContent = { Text(hit.file.name) },
                    supportingContent = {
                        Column {
                            Text("${hit.file.kind.name} · ${hit.locator} · score ${"%.3f".format(hit.score)}")
                            hit.snippet?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }
                    },
                )
            }
            item {
                Text("Log", style = MaterialTheme.typography.titleMedium)
            }
            item {
                Text(
                    runner.log.takeLast(200).joinToString("\n"),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }
    }
}
