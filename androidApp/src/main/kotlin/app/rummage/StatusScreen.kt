package app.rummage

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.sync.withLock
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import app.rummage.core.DeviceProfile
import app.rummage.core.Kind
import app.rummage.core.Prefs
import app.rummage.core.prefs
import app.rummage.core.setPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatusUi(
    val byState: Map<String, Long> = emptyMap(),
    val byKind: Map<Kind, Long> = emptyMap(),
    val prefs: Prefs = Prefs(),
    val profile: DeviceProfile? = null,
)

private const val SAMPLE_PER_KIND = 50

private val SEARCH_INSIDE = listOf(Kind.PDF, Kind.DOC, Kind.SCREENSHOT, Kind.PHOTO, Kind.AUDIO, Kind.VIDEO)

class StatusViewModel(private val app: Application) : AndroidViewModel(app) {
    val graph = (app as RummageApp).graph
    private val _ui = MutableStateFlow(StatusUi())
    val ui: StateFlow<StatusUi> = _ui
    private val _tune = MutableStateFlow<TuneState>(TuneState.Idle)
    val tune: StateFlow<TuneState> = _tune

    init {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                reload()
                delay(2000)
            }
        }
    }

    private fun reload() {
        val store = graph.store
        _ui.value = StatusUi(store.countsByState(), store.countsByKind(), store.prefs(), store.profile())
    }

    fun indexNow() {
        viewModelScope.launch(Dispatchers.IO) { IndexScheduler.resumeByUser(app) }
    }

    fun pause() {
        viewModelScope.launch(Dispatchers.IO) { IndexScheduler.pauseByUser(app) }
    }

    /** Waits for any running batch, so nothing is embedding when the shared model closes. */
    suspend fun openSpikeTools() {
        IndexScheduler.pause(app)
        graph.indexLock.withLock { graph.releaseEmbedder() }
    }

    fun setSearchInside(kind: Kind, on: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val p = graph.store.prefs()
            val next = p.copy(kinds = if (on) p.kinds + kind else p.kinds - kind)
            graph.store.setPrefs(next)
            _ui.update { it.copy(prefs = next) }
            if (on && kind !in p.kinds) {
                graph.store.requeue(setOf(kind))
                IndexScheduler.indexNow(app)
            }
        }
    }

    fun setOnlyWhileCharging(on: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val next = graph.store.prefs().copy(onlyWhileCharging = on)
            graph.store.setPrefs(next)
            _ui.update { it.copy(prefs = next) }
            IndexScheduler.schedulePeriodic(app, on)
        }
    }

    // Restarts indexing so the next sync trims to (or grows back from) the sample right away.
    fun setSampleMode(on: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val next = graph.store.prefs().copy(sampleSize = if (on) SAMPLE_PER_KIND else null)
            graph.store.setPrefs(next)
            _ui.update { it.copy(prefs = next) }
            IndexScheduler.pause(app)
            IndexScheduler.indexNow(app)
            IndexScheduler.schedulePeriodic(app, next.onlyWhileCharging)
        }
    }

    // The next sync sees the changed exclude list and does a full scan, which brings the folder back.
    fun removeExcluded(folder: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val p = graph.store.prefs()
            val next = p.copy(excludedFolders = p.excludedFolders - folder)
            graph.store.setPrefs(next)
            _ui.update { it.copy(prefs = next) }
            IndexScheduler.indexNow(app)
        }
    }

    fun tuneAgain() {
        if (_tune.value is TuneState.Running) return
        _tune.value = TuneState.Running
        // The app scope: leaving Status must not cancel a tune halfway (indexing is paused meanwhile).
        graph.scope.launch(Dispatchers.Default) {
            _tune.value = try {
                val r = calibrate(graph, app, tuneAgain = true)
                reload()
                TuneState.Done(tuneSummary(r.chosen, r.backends.firstOrNull { it.backend == r.chosen }?.imageMs))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Rummage", "calibration failed", e)
                TuneState.Failed(e.message ?: e.toString())
            }
        }
    }
}

internal fun stopCopy(stop: String?): String? = when (stop) {
    "DONE" -> "All caught up"
    "HOT" -> "Resting while the phone cools down"
    "UNPLUGGED" -> "Waiting for the charger"
    "BUDGET" -> "Taking a short break"
    "PAUSED" -> "Paused"
    else -> null
}

private fun n(v: Long) = "%,d".format(v)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(onBack: () -> Unit, vm: StatusViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val tune by vm.tune.collectAsStateWithLifecycle()
    val index by vm.graph.indexState.collectAsStateWithLifecycle()

    val searchable = ui.byState["indexed"] ?: 0
    val waiting = ui.byState["pending"] ?: 0
    val skipped = ui.byState.filterKeys { it != "indexed" && it != "pending" }.values.sum()
    val counts = buildString {
        append("${n(searchable)} searchable · ${n(waiting)} waiting")
        if (skipped > 0) append(" · ${n(skipped)} skipped")
    }
    val kinds = ui.byKind.entries.sortedByDescending { it.value }
        .joinToString(" · ") { (kind, count) -> "${n(count)} ${Format.kindLabelPlural(kind).lowercase()}" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Status") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(counts, style = MaterialTheme.typography.titleMedium.tabular(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (kinds.isNotEmpty()) {
                    Text(kinds, style = MaterialTheme.typography.bodyMedium.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // The bar and the line below it stay in place whether or not a batch is running, so nothing
                // under them jumps each time indexing pauses between batches.
                val total = index.pendingAtStart
                val fraction = if (index.running && total > 0) (index.processed.toFloat() / total).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().alpha(if (index.running) 1f else 0f),
                )
                Text(
                    when {
                        index.running && total == 0L -> "Looking for your files…"
                        index.running -> "${n(index.processed.toLong())} of ${n(total)}" + (index.current?.let { " · $it" } ?: "")
                        else -> stopCopy(if (ui.prefs.paused) "PAUSED" else index.lastStop) ?: " "
                    },
                    style = MaterialTheme.typography.bodyMedium.tabular(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (index.failed > 0) {
                    Text("${n(index.failed.toLong())} couldn't be read", style = MaterialTheme.typography.bodySmall)
                }
                index.lastError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::indexNow) { Text("Index now") }
                    OutlinedButton(onClick = vm::pause) { Text("Pause") }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Header("Search inside")
            for (kind in SEARCH_INSIDE) {
                SwitchRow(Format.kindLabelPlural(kind), kind in ui.prefs.kinds) { vm.setSearchInside(kind, it) }
            }
            Text(
                "Everything else is found by name only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SwitchRow("Only index while charging", ui.prefs.onlyWhileCharging, vm::setOnlyWhileCharging)
            SwitchRow("Sample mode: newest $SAMPLE_PER_KIND of each type", ui.prefs.sampleSize != null, vm::setSampleMode)
            Text(
                "For testing. Indexes a few hundred files in minutes. Turn it off to bring in the whole phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            if (ui.prefs.excludedFolders.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Header("Folders Rummage skips")
                for (folder in ui.prefs.excludedFolders.sorted()) {
                    ListItem(
                        headlineContent = { Text(folder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = {
                            IconButton(onClick = { vm.removeExcluded(folder) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Search $folder again")
                            }
                        },
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Header("This phone")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ui.profile?.let { p ->
                    Text(
                        "Backend ${p.backend} · tier ${p.tier} · ${p.visionTokens} vision tokens",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                when (val t = tune) {
                    TuneState.Running -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        Text("Tuning… this takes about a minute", style = MaterialTheme.typography.bodyMedium)
                    }
                    is TuneState.Failed -> {
                        Text("Couldn't tune Rummage for this phone", color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = vm::tuneAgain) { Text("Try again") }
                    }
                    else -> {
                        (t as? TuneState.Done)?.let { Text(it.summary, style = MaterialTheme.typography.bodyMedium) }
                        OutlinedButton(onClick = vm::tuneAgain) { Text("Tune again") }
                    }
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        vm.openSpikeTools()
                        context.startActivity(Intent(context, SpikeActivity::class.java))
                    }
                }) { Text("Spike tools") }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    // One toggleable row, so TalkBack reads the label with the switch state.
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}
