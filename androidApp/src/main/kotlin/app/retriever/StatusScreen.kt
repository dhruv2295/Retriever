package app.retriever

import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.BatteryAlert
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
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
    private val _skipped = MutableStateFlow<List<Store.StoredFile>>(emptyList())
    val skipped: StateFlow<List<Store.StoredFile>> = _skipped

    fun resume() {
        viewModelScope.launch { IndexScheduler.resumeByUser(app) }
    }

    fun pause() {
        viewModelScope.launch { IndexScheduler.pauseByUser(app) }
    }

    fun loadSkipped() {
        viewModelScope.launch { _skipped.value = retriever.skipped(50) }
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
            IndexScheduler.indexNow(app)
            _skipped.value = emptyList()
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
    StopReason.BUSY -> "Paused while you use your phone"
    StopReason.PAUSED -> "Paused"
    null -> null
}

/** Why files are still waiting. A batch that ended DONE finished before the newer files arrived. */
internal fun waitingCopy(stop: StopReason?): String? = if (stop == StopReason.DONE) "Will read the new files soon" else stopCopy(stop)

class StatusActions(
    val back: () -> Unit = {},
    val pause: () -> Unit = {},
    val resume: () -> Unit = {},
    val toggleSkipped: () -> Unit = {},
    val retrySkipped: () -> Unit = {},
    val setSearchInside: (Kind, Boolean) -> Unit = { _, _ -> },
    val setOnlyWhileCharging: (Boolean) -> Unit = {},
    val removeExcluded: (String) -> Unit = {},
    val openBatterySettings: () -> Unit = {},
    val setSampleMode: (Boolean) -> Unit = {},
    val tuneAgain: () -> Unit = {},
)

@Composable
fun StatusScreen(onBack: () -> Unit, vm: StatusViewModel = appViewModel { StatusViewModel(it) }) {
    val context = LocalContext.current
    val counts by vm.counts.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val tune by vm.tune.collectAsStateWithLifecycle()
    val index by vm.index.collectAsStateWithLifecycle()
    val skippedFiles by vm.skipped.collectAsStateWithLifecycle()
    var skippedOpen by rememberSaveable { mutableStateOf(false) }
    val power = remember { context.getSystemService(PowerManager::class.java) }
    var unrestricted by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        unrestricted = power.isIgnoringBatteryOptimizations(context.packageName)
        if (skippedOpen) vm.loadSkipped()
    }
    StatusContent(
        counts = counts,
        prefs = prefs,
        index = index,
        tune = tune,
        skippedFiles = skippedFiles,
        skippedOpen = skippedOpen,
        batteryHint = !unrestricted,
        actions = StatusActions(
            back = onBack,
            pause = vm::pause,
            resume = vm::resume,
            toggleSkipped = {
                skippedOpen = !skippedOpen
                if (skippedOpen) vm.loadSkipped()
            },
            retrySkipped = vm::retrySkipped,
            setSearchInside = vm::setSearchInside,
            setOnlyWhileCharging = vm::setOnlyWhileCharging,
            removeExcluded = vm::removeExcluded,
            openBatterySettings = { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
            setSampleMode = vm::setSampleMode,
            tuneAgain = vm::tuneAgain,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusContent(
    counts: Store.Counts,
    prefs: Prefs,
    index: IndexState,
    tune: TuneState,
    skippedFiles: List<Store.StoredFile>,
    skippedOpen: Boolean,
    batteryHint: Boolean,
    actions: StatusActions = StatusActions(),
) {
    val searchable = counts.byState[FileState.INDEXED] ?: 0
    val waiting = counts.byState[FileState.PENDING] ?: 0
    val skipped = counts.byState[FileState.SKIPPED] ?: 0
    val total = searchable + waiting + skipped

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            index.running && index.pendingAtStart == 0L -> "Looking for your files"
                            prefs.paused -> "Paused"
                            waiting > 0 -> "Reading your files"
                            else -> "All caught up"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            // Capped inside the scroll, so the gutters scroll too.
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).wrapContentWidth().widthIn(max = 600.dp).fillMaxWidth().padding(bottom = 24.dp)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (index.running) {
                        Image(
                            painterResource(R.drawable.illus_reading),
                            contentDescription = null,
                            modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = 160.dp).fillMaxWidth(),
                        )
                    }
                    Text(
                        "${Format.count(searchable)} of ${Format.count(total)} files searchable",
                        style = MaterialTheme.typography.titleMedium.tabular(),
                    )
                    LinearProgressIndicator(
                        progress = { if (total > 0) searchable.toFloat() / total else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    when {
                        index.running && index.pendingAtStart == 0L -> Muted("Looking for your files…")
                        index.running -> {
                            index.etaMinutes?.let { Muted(Format.eta(it).replaceFirstChar(Char::uppercase)) }
                            index.current?.let { Muted("Now: $it", maxLines = 1) }
                        }
                        // The title already says paused or all caught up.
                        waiting > 0 && !prefs.paused -> waitingCopy(index.lastStop)?.let { Muted(it) }
                    }
                    index.lastError?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                    when {
                        prefs.paused -> Button(onClick = actions.resume) { Text("Resume") }
                        waiting > 0 -> OutlinedButton(onClick = actions.pause) { Text("Pause") }
                    }
                }

                if (skipped > 0) {
                    ListItem(
                        headlineContent = {
                            Text(if (skipped == 1L) "1 file couldn't be read" else "${Format.count(skipped)} files couldn't be read")
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(if (skippedOpen) "Hide" else "See which", color = MaterialTheme.colorScheme.primary)
                                Icon(if (skippedOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                            }
                        },
                        modifier = Modifier
                            .clickable(onClick = actions.toggleSkipped)
                            .semantics { stateDescription = if (skippedOpen) "Expanded" else "Collapsed" },
                    )
                    AnimatedVisibility(skippedOpen) {
                        Column {
                            for (f in skippedFiles) {
                                ListItem(
                                    headlineContent = { Text(f.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = { Text(f.file.folder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                )
                            }
                            TextButton(onClick = actions.retrySkipped, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Try again") }
                        }
                    }
                }

                if (counts.tailChunks > 0) {
                    Muted(
                        "Some long documents are only partly read. Retriever reads the rest while your phone charges.",
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                if (batteryHint && waiting > 0) {
                    ListItem(
                        headlineContent = { Text("Let Retriever keep reading in the background") },
                        supportingContent = { Text("Some phones stop apps working in the background.") },
                        leadingContent = { Icon(Icons.Outlined.BatteryAlert, contentDescription = null) },
                        modifier = Modifier.clickable(onClick = actions.openBatterySettings),
                    )
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Header("Read inside")
                for (kind in SEARCH_INSIDE) {
                    SwitchRow(Format.kindLabelPlural(kind), kind in prefs.kinds) { actions.setSearchInside(kind, it) }
                }
                Muted("Everything else is found by name.", Modifier.padding(horizontal = 16.dp))

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SwitchRow("Only read while charging", prefs.onlyWhileCharging, actions.setOnlyWhileCharging)

                if (prefs.excludedFolders.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Header("Folders Retriever skips")
                    for (folder in prefs.excludedFolders.sorted()) {
                        ListItem(
                            headlineContent = { Text(folder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingContent = {
                                IconButton(onClick = { actions.removeExcluded(folder) }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Search $folder again")
                                }
                            },
                        )
                    }
                }

                // Unreachable in release. Compose lifts some lambdas out of the branch, so only R8 drops their strings.
                if (BuildConfig.DEBUG) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Header("For testing")
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        index.backend?.let { Text(tuneSummary(it, null), style = MaterialTheme.typography.bodyMedium) }
                        index.detail?.let { Muted(it.replaceFirstChar(Char::uppercase)) }
                        index.build?.let { Muted("Model file: ${it.fileName}") }
                    }
                    SwitchRow("Sample mode: newest $SAMPLE_PER_KIND of each type", prefs.sampleSize != null, actions.setSampleMode)
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (tune) {
                            TuneState.Running -> Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                                Text("Tuning… about a minute", style = MaterialTheme.typography.bodyMedium)
                            }
                            is TuneState.Failed -> Text("Tune failed: ${tune.message}", color = MaterialTheme.colorScheme.error)
                            is TuneState.Done -> Muted(tune.summary)
                            TuneState.Idle -> Unit
                        }
                        OutlinedButton(onClick = actions.tuneAgain, enabled = tune != TuneState.Running) { Text("Tune again") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
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
