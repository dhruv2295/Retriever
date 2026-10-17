package app.retriever

import android.app.DownloadManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.retriever.core.IndexState
import app.retriever.core.Kind
import app.retriever.core.ModelBuild
import app.retriever.core.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SAMPLE_PER_KIND = 50

/** The order Settings lists the "Read inside" switches in; which of them show is core's [app.retriever.core.Retriever.readableKinds]. */
private val SEARCH_INSIDE = listOf(Kind.PDF, Kind.DOC, Kind.SCREENSHOT, Kind.PHOTO, Kind.AUDIO, Kind.VIDEO)

private const val NOTICES_ASSET = "THIRD_PARTY_NOTICES.md"
private const val STOPPED = "The download stopped. Check your connection and try again."

/** The model coming back after Free up space. Outlives the screen, like [SetupFlow]'s chain: leaving must not drop a download. */
private object Refetch {
    val state = MutableStateFlow<ModelState>(ModelState.Missing)
    var job: Job? = null
    var id: Long? = null
}

class SettingsViewModel(private val graph: AppGraph) : ViewModel() {
    private val app = graph.app
    private val retriever = graph.retriever
    val prefs: StateFlow<Prefs> = retriever.prefs
    val index: StateFlow<IndexState> = retriever.state
    val modelMissing: StateFlow<Boolean> = retriever.modelMissing
    val build: StateFlow<ModelBuild> = retriever.requiredBuild
    val download: StateFlow<ModelState> = Refetch.state
    val textOnly = retriever.textOnly
    val readable = retriever.readableKinds
    val ramGb = retriever.probe.ramGb
    private val _tune = MutableStateFlow<TuneState>(TuneState.Idle)
    val tune: StateFlow<TuneState> = _tune
    private val _storage = MutableStateFlow<StorageUsage?>(null)
    val storage: StateFlow<StorageUsage?> = _storage

    private suspend fun loadStorage() {
        _storage.value = withContext(Dispatchers.IO) { storageUsage(app, graph.modelDir, retriever.requiredBuild.value) }
    }

    fun refreshStorage() {
        viewModelScope.launch { loadStorage() }
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

    fun setOnlyWhileCharging(on: Boolean) {
        viewModelScope.launch {
            retriever.updatePrefs { it.copy(onlyWhileCharging = on) }
            IndexScheduler.schedulePeriodic(app, on)
        }
    }

    // Turning it off clears the saved searches; Retriever.updatePrefs does that.
    fun setRememberSearches(on: Boolean) {
        viewModelScope.launch { retriever.updatePrefs { it.copy(rememberSearches = on) } }
    }

    fun setMobileData(on: Boolean) {
        viewModelScope.launch { retriever.updatePrefs { it.copy(allowMobileData = on) } }
    }

    fun setDynamicColor(on: Boolean) {
        viewModelScope.launch { retriever.updatePrefs { it.copy(dynamicColor = on) } }
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
        // The app scope: leaving Settings must not cancel a tune halfway (indexing waits meanwhile).
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

    /** Stops the running batch and frees the model, then deletes it; names and words keep searching. */
    fun freeUpSpace(done: () -> Unit) {
        graph.scope.launch {
            retriever.cancelBatch()
            retriever.release()
            withContext(Dispatchers.IO) { graph.download.deleteModel(retriever.requiredBuild.value) }
            retriever.refreshSetUp()
            loadStorage()
            done()
        }
    }

    init {
        // A download this screen started before the process was killed carries on in DownloadManager: show and finish it.
        if (Refetch.job?.isActive != true && retriever.modelMissing.value) {
            viewModelScope.launch { if (withContext(Dispatchers.IO) { graph.download.active(retriever.requiredBuild.value) } != null) downloadAgain() }
        }
    }

    fun downloadAgain() {
        if (Refetch.job?.isActive == true) return
        Refetch.job = graph.scope.launch {
            val b = retriever.requiredBuild.value
            val download = graph.download
            val running = withContext(Dispatchers.IO) { download.active(b) }
            if (running == null && !download.hasSpaceFor(b)) {
                Refetch.state.value = ModelState.Failed(spaceMessage(b))
                return@launch
            }
            Refetch.state.value = ModelState.Downloading(0f, true)
            val id = try {
                running ?: download.start(b, retriever.prefs.value)
            } catch (e: Exception) {
                Log.e("Retriever", "download could not start", e)
                Refetch.state.value = ModelState.Failed(STOPPED)
                return@launch
            }
            Refetch.id = id
            var finished = false
            download.progress(id).collect { p ->
                finished = p.done
                if (!p.done && !p.failed) {
                    val fraction = if (p.total > 0) (p.bytes.toFloat() / p.total).coerceIn(0f, 1f) else 0f
                    Refetch.state.value = if (p.waitingForWifi) ModelState.WaitingForWifi else ModelState.Downloading(fraction, p.total <= 0)
                }
            }
            Refetch.id = null
            Refetch.state.value = ModelState.Checking
            if (finished && download.verify(b)) {
                withContext(Dispatchers.IO) { download.prune(keep = b) }
                retriever.refreshSetUp()
                Refetch.state.value = ModelState.Missing
                loadStorage()
                // Files read by name alone while the model was gone get their meaning now.
                IndexScheduler.indexNow(app)
            } else {
                app.getSystemService(DownloadManager::class.java).remove(id)
                withContext(Dispatchers.IO) { download.file(b).delete() }
                Refetch.state.value = ModelState.Failed(if (finished) "The download was damaged. Try again." else STOPPED)
            }
        }
    }

    fun cancelDownload() {
        graph.scope.launch {
            Refetch.job?.cancelAndJoin()
            val b = retriever.requiredBuild.value
            Refetch.id?.let { app.getSystemService(DownloadManager::class.java).remove(it) }
            Refetch.id = null
            withContext(Dispatchers.IO) { graph.download.file(b).delete() }
            Refetch.state.value = ModelState.Missing
        }
    }
}

/** Everything the Settings page shows, so a screenshot test can set it up without a graph. */
data class SettingsUi(
    val prefs: Prefs,
    val index: IndexState = IndexState(),
    val tune: TuneState = TuneState.Idle,
    val storage: StorageUsage? = null,
    val download: ModelState = ModelState.Missing,
    val modelMissing: Boolean = false,
    val build: ModelBuild? = null,
    val textOnly: Boolean = false,
    val ramGb: Double = 0.0,
    val confirmFree: Boolean = false,
    /** The kinds this phone can read inside, so the switches it may show. */
    val readable: Set<Kind> = Kind.entries.toSet(),
)

class SettingsActions(
    val back: () -> Unit = {},
    val setSearchInside: (Kind, Boolean) -> Unit = { _, _ -> },
    val setOnlyWhileCharging: (Boolean) -> Unit = {},
    val removeExcluded: (String) -> Unit = {},
    val tuneAgain: () -> Unit = {},
    val setRememberSearches: (Boolean) -> Unit = {},
    val setMobileData: (Boolean) -> Unit = {},
    val setDynamicColor: (Boolean) -> Unit = {},
    val setSampleMode: (Boolean) -> Unit = {},
    val askFreeUpSpace: () -> Unit = {},
    val dismissFreeUpSpace: () -> Unit = {},
    val freeUpSpace: () -> Unit = {},
    val downloadAgain: () -> Unit = {},
    val cancelDownload: () -> Unit = {},
    val openNotices: () -> Unit = {},
)

@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = appViewModel { SettingsViewModel(it) }) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val index by vm.index.collectAsStateWithLifecycle()
    val tune by vm.tune.collectAsStateWithLifecycle()
    val storage by vm.storage.collectAsStateWithLifecycle()
    val download by vm.download.collectAsStateWithLifecycle()
    val modelMissing by vm.modelMissing.collectAsStateWithLifecycle()
    val build by vm.build.collectAsStateWithLifecycle()
    var confirmFree by rememberSaveable { mutableStateOf(false) }
    var showNotices by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshStorage() }
    // Read from the assets only when asked for: 22 KB nobody opens on most visits.
    val context = LocalContext.current
    val notices by produceState<String?>(null, showNotices) {
        if (showNotices) value = withContext(Dispatchers.IO) { context.assets.open(NOTICES_ASSET).bufferedReader().use { it.readText() } }
    }
    if (showNotices) {
        BackHandler { showNotices = false }
        NoticesContent(notices) { showNotices = false }
        return
    }
    SettingsContent(
        ui = SettingsUi(prefs, index, tune, storage, download, modelMissing, build, vm.textOnly, vm.ramGb, confirmFree, vm.readable),
        actions = SettingsActions(
            back = onBack,
            setSearchInside = vm::setSearchInside,
            setOnlyWhileCharging = vm::setOnlyWhileCharging,
            removeExcluded = vm::removeExcluded,
            tuneAgain = vm::tuneAgain,
            setRememberSearches = vm::setRememberSearches,
            setMobileData = vm::setMobileData,
            setDynamicColor = vm::setDynamicColor,
            setSampleMode = vm::setSampleMode,
            askFreeUpSpace = { confirmFree = true },
            dismissFreeUpSpace = { confirmFree = false },
            freeUpSpace = {
                confirmFree = false
                vm.freeUpSpace { scope.launch { snackbar.showSnackbar("Model removed") } }
            },
            downloadAgain = vm::downloadAgain,
            cancelDownload = vm::cancelDownload,
            openNotices = { showNotices = true },
        ),
        snackbar = snackbar,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(ui: SettingsUi, actions: SettingsActions = SettingsActions(), snackbar: SnackbarHostState = remember { SnackbarHostState() }) {
    val prefs = ui.prefs
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            // Capped inside the scroll, so the gutters scroll too.
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).wrapContentWidth().widthIn(max = 600.dp).fillMaxWidth().padding(bottom = 24.dp)) {
                Header("Read inside")
                if (ui.textOnly) Muted(textOnlyNote(ui.ramGb), Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                for (kind in SEARCH_INSIDE.filter { it in ui.readable }) {
                    SwitchRow(Format.kindLabelPlural(kind), kind in prefs.kinds) { actions.setSearchInside(kind, it) }
                }
                Muted("Everything else is found by name.", Modifier.padding(horizontal = 16.dp))

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Header("Reading")
                SwitchRow(
                    "Only read while charging",
                    prefs.onlyWhileCharging,
                    "Reading uses battery. This waits for the charger, or for when you open the app.",
                    actions.setOnlyWhileCharging,
                )
                if (!ui.textOnly && !ui.modelMissing) TuneRow(ui.tune, actions.tuneAgain)

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

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Header("Privacy")
                SwitchRow(
                    "Remember searches",
                    prefs.rememberSearches,
                    "Saved on this phone only. Nothing is sent anywhere.",
                    actions.setRememberSearches,
                )

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Header("Storage")
                Storage(ui, actions)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Header("Appearance")
                    SwitchRow("Wallpaper colours", prefs.dynamicColor, "Off uses Retriever's warm amber.", actions.setDynamicColor)
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Header("About")
                ValueRow("Version", BuildConfig.VERSION_NAME)
                ListItem(
                    headlineContent = { Text("Open-source notices") },
                    modifier = Modifier.clickable(role = Role.Button, onClick = actions.openNotices),
                )
                Muted(
                    "Retriever reads your files on this phone. Search, indexing and stars never leave it. The only network use is downloading the search model.",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )

                // Unreachable in release. Compose lifts some lambdas out of the branch, so only R8 drops their strings.
                if (BuildConfig.DEBUG) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Header("For testing")
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ui.index.backend?.let { Text(tuneSummary(it, null), style = MaterialTheme.typography.bodyMedium) }
                        ui.index.detail?.let { Muted(it.replaceFirstChar(Char::uppercase)) }
                        ui.index.build?.let { Muted("Model file: ${it.fileName}") }
                    }
                    SwitchRow("Sample mode: newest $SAMPLE_PER_KIND of each type", prefs.sampleSize != null, onChange = actions.setSampleMode)
                }
            }
        }
    }
    if (ui.confirmFree) {
        AlertDialog(
            onDismissRequest = actions.dismissFreeUpSpace,
            title = { Text("Remove the search model?") },
            text = {
                val size = ui.build?.let { " (about ${Format.bytes(it.sizeBytes)})" }.orEmpty()
                Text("Searching by meaning and by what is in photos stops until the model downloads again$size, while names and words keep working.")
            },
            confirmButton = { TextButton(onClick = actions.freeUpSpace) { Text("Remove") } },
            dismissButton = { TextButton(onClick = actions.dismissFreeUpSpace) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TuneRow(tune: TuneState, onTune: () -> Unit) {
    ListItem(
        headlineContent = { Text("Tune again") },
        supportingContent = {
            Text(
                when (tune) {
                    TuneState.Running -> "Tuning… a few minutes at most"
                    is TuneState.Failed -> "Tune failed: ${tune.message}"
                    is TuneState.Done -> tune.summary
                    TuneState.Idle -> "Re-check which chip runs the model fastest. Takes a few minutes."
                },
                color = if (tune is TuneState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = if (tune == TuneState.Running) {
            { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp) }
        } else {
            null
        },
        modifier = Modifier.clickable(enabled = tune != TuneState.Running, role = Role.Button, onClick = onTune),
    )
}

@Composable
private fun Storage(ui: SettingsUi, actions: SettingsActions) {
    ValueRow("Search index", ui.storage?.let { Format.bytes(it.indexBytes) } ?: "…")
    if (ui.textOnly) return
    ValueRow("Search model", ui.storage?.let { if (it.modelBytes > 0) Format.bytes(it.modelBytes) else "Not downloaded" } ?: "…")
    ui.storage?.cacheBytes?.takeIf { it > 0 }?.let { ValueRow("Temporary files", Format.bytes(it)) }
    val sizeNote = ui.build?.let { "About ${Format.bytes(it.sizeBytes)}. " }.orEmpty()
    when (val d = ui.download) {
        is ModelState.Downloading, ModelState.WaitingForWifi, ModelState.Checking -> {
            ListItem(
                headlineContent = {
                    Text(
                        when (d) {
                            is ModelState.Downloading -> if (d.indeterminate) "Downloading the search model" else "Downloading the search model, ${(d.progress * 100).toInt()}%"
                            ModelState.WaitingForWifi -> "Waiting for Wi-Fi"
                            else -> "Checking the download"
                        },
                    )
                },
                trailingContent = if (d == ModelState.Checking) null else ({ TextButton(onClick = actions.cancelDownload) { Text("Cancel") } }),
            )
            val bar = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            val fraction = (d as? ModelState.Downloading)?.takeUnless { it.indeterminate }?.progress
            if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = bar) else LinearProgressIndicator(bar)
        }
        else -> if (ui.modelMissing) {
            ListItem(
                headlineContent = { Text("Download again", color = MaterialTheme.colorScheme.primary) },
                supportingContent = {
                    val failed = d as? ModelState.Failed
                    Text(
                        failed?.reason ?: (sizeNote + "Brings back search by meaning and by what is in photos."),
                        color = if (failed != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                modifier = Modifier.clickable(role = Role.Button, onClick = actions.downloadAgain),
            )
        } else if ((ui.storage?.modelBytes ?: 0) > 0) {
            ListItem(
                headlineContent = { Text("Free up space", color = MaterialTheme.colorScheme.error) },
                supportingContent = { Text("Removes the search model. Names and words stay searchable.") },
                modifier = Modifier.clickable(role = Role.Button, onClick = actions.askFreeUpSpace),
            )
        }
    }
    SwitchRow("Use mobile data", ui.prefs.allowMobileData, "Off: the search model downloads on Wi-Fi only.", actions.setMobileData)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoticesContent(text: String?, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Open-source notices") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(text ?: "Loading…", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ValueRow(label: String, value: String) {
    ListItem(headlineContent = { Text(label) }, trailingContent = { Text(value, style = MaterialTheme.typography.bodyMedium.tabular()) })
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, supporting: String? = null, onChange: (Boolean) -> Unit) {
    // One toggleable row, so TalkBack reads the label with the switch state.
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}
