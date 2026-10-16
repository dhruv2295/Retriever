package app.retriever

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
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.retriever.core.FileState
import app.retriever.core.IndexState
import app.retriever.core.Kind
import app.retriever.core.Prefs
import app.retriever.core.StopReason
import app.retriever.core.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val SAMPLE_PER_KIND = 50

private val SEARCH_INSIDE = listOf(Kind.PDF, Kind.DOC, Kind.SCREENSHOT, Kind.PHOTO, Kind.AUDIO, Kind.VIDEO)

class StatusViewModel(private val graph: AppGraph) : ViewModel() {
    private val app = graph.app
    private val retriever = graph.retriever
    val counts: StateFlow<Store.Counts> =
        retriever.counts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Store.Counts(emptyMap(), emptyMap(), 0))
    val prefs: StateFlow<Prefs> = retriever.prefs
    val index: StateFlow<IndexState> = retriever.state
    private val _tune = MutableStateFlow<TuneState>(TuneState.Idle)
    val tune: StateFlow<TuneState> = _tune

    fun indexNow() {
        viewModelScope.launch { IndexScheduler.resumeByUser(app) }
    }

    fun pause() {
        viewModelScope.launch { IndexScheduler.pauseByUser(app) }
    }

    fun setSearchInside(kind: Kind, on: Boolean) {
        viewModelScope.launch {
            val wasOn = kind in retriever.prefs.value.kinds
            retriever.updatePrefs { p -> p.copy(kinds = if (on) p.kinds + kind else p.kinds - kind) }
            if (on && !wasOn) {
                retriever.requeue(setOf(kind))
                IndexScheduler.indexNow(app)
            }
        }
    }

    fun retrySkipped() {
        viewModelScope.launch {
            retriever.retrySkipped()
            IndexScheduler.resumeByUser(app)
        }
    }

    fun setOnlyWhileCharging(on: Boolean) {
        viewModelScope.launch {
            retriever.updatePrefs { it.copy(onlyWhileCharging = on) }
            IndexScheduler.schedulePeriodic(app, on)
        }
    }

    // Restarts indexing so the next sync trims to (or grows back from) the sample right away.
    fun setSampleMode(on: Boolean) {
        viewModelScope.launch {
            retriever.updatePrefs { it.copy(sampleSize = if (on) SAMPLE_PER_KIND else null) }
            IndexScheduler.pause(app)
            IndexScheduler.indexNow(app)
            IndexScheduler.schedulePeriodic(app, retriever.prefs.value.onlyWhileCharging)
        }
    }

    // The next sync sees the changed exclude list and does a full scan, which brings the folder back.
    fun removeExcluded(folder: String) {
        viewModelScope.launch {
            retriever.updatePrefs { it.copy(excludedFolders = it.excludedFolders - folder) }
            IndexScheduler.indexNow(app)
        }
    }

    fun tuneAgain() {
        if (_tune.value is TuneState.Running) return
        _tune.value = TuneState.Running
        // The app scope: leaving Status must not cancel a tune halfway (indexing waits meanwhile).
        graph.scope.launch {
            _tune.value = try {
                val r = calibrate(graph, app, tuneAgain = true)
                TuneState.Done(tuneSummary(r.chosen, r.backends.firstOrNull { it.backend == r.chosen }?.imageMs))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e("Retriever", "calibration failed", e)
                TuneState.Failed(e.message ?: e.toString())
            }
        }
    }
}

internal fun stopCopy(stop: StopReason?): String? = when (stop) {
    StopReason.DONE -> "All caught up"
    StopReason.HOT -> "Resting while the phone cools down"
    StopReason.UNPLUGGED -> "Waiting for the charger"
    StopReason.BUDGET -> "Taking a short break"
    StopReason.BUSY -> "Waiting until you put the phone down"
    StopReason.PAUSED -> "Paused"
    null -> null
}

private fun n(v: Long) = "%,d".format(v)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(onBack: () -> Unit, vm: StatusViewModel = appViewModel { StatusViewModel(it) }) {
    val counts by vm.counts.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val tune by vm.tune.collectAsStateWithLifecycle()
    val index by vm.index.collectAsStateWithLifecycle()

    val searchable = counts.byState[FileState.INDEXED] ?: 0
    val waiting = counts.byState[FileState.PENDING] ?: 0
    val skipped = counts.byState[FileState.SKIPPED] ?: 0
    val summary = buildString {
        append("${n(searchable)} searchable · ${n(waiting)} waiting")
        if (skipped > 0) append(" · ${n(skipped)} skipped")
    }
    val kinds = counts.byKind.entries.sortedByDescending { it.value }
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
                Text(summary, style = MaterialTheme.typography.titleMedium.tabular(), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                        else -> stopCopy(if (prefs.paused) StopReason.PAUSED else index.lastStop) ?: " "
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
                if (skipped > 0) {
                    TextButton(onClick = vm::retrySkipped) { Text("Try skipped files again") }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Header("Search inside")
            for (kind in SEARCH_INSIDE) {
                SwitchRow(Format.kindLabelPlural(kind), kind in prefs.kinds) { vm.setSearchInside(kind, it) }
            }
            Text(
                "Everything else is found by name only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SwitchRow("Only index while charging", prefs.onlyWhileCharging, vm::setOnlyWhileCharging)
            SwitchRow("Sample mode: newest $SAMPLE_PER_KIND of each type", prefs.sampleSize != null, vm::setSampleMode)
            Text(
                "For testing. Indexes a few hundred files in minutes. Turn it off to bring in the whole phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            if (prefs.excludedFolders.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Header("Folders Retriever skips")
                for (folder in prefs.excludedFolders.sorted()) {
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
                index.backend?.let { b ->
                    Text(tuneSummary(b, null), style = MaterialTheme.typography.bodyMedium)
                }
                index.detail?.let { Text(it.replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.bodySmall) }
                when (val t = tune) {
                    TuneState.Running -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        Text("Tuning… this takes about a minute", style = MaterialTheme.typography.bodyMedium)
                    }
                    is TuneState.Failed -> {
                        Text("Couldn't tune Retriever for this phone", color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = vm::tuneAgain) { Text("Try again") }
                    }
                    else -> {
                        (t as? TuneState.Done)?.let { Text(it.summary, style = MaterialTheme.typography.bodyMedium) }
                        OutlinedButton(onClick = vm::tuneAgain) { Text("Tune again") }
                    }
                }
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
