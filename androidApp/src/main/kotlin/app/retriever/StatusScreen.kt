package app.retriever

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import app.retriever.core.Prefs
import app.retriever.core.StopReason
import app.retriever.core.Store
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class StatusViewModel(private val graph: AppGraph) : ViewModel() {
    private val app = graph.app
    private val retriever = graph.retriever
    val counts: StateFlow<Store.Counts> =
        retriever.counts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Store.Counts(emptyMap(), emptyMap(), 0))
    val prefs: StateFlow<Prefs> = retriever.prefs
    val index: StateFlow<IndexState> = retriever.state
    private val _skipped = MutableStateFlow<List<Store.StoredFile>>(emptyList())
    val skipped: StateFlow<List<Store.StoredFile>> = _skipped
    private val _access = MutableStateFlow(graph.access())
    val access: StateFlow<Access> = _access
    private val _folderError = MutableStateFlow<String?>(null)
    val folderError: StateFlow<String?> = _folderError
    val textOnly = retriever.textOnly

    /** Permissions can change in Settings while Status is in the background; RetrieverApp syncs for that. */
    fun refreshAccess() {
        _access.value = graph.access()
    }

    /** The picker can reveal more photos without changing any permission, so this always syncs. */
    fun photosPicked() {
        graph.accessChanged()
        refreshAccess()
    }

    fun addFolder(uri: Uri) {
        _folderError.value = graph.addFolder(uri)
        _access.value = graph.access()
    }

    fun removeFolder(uri: String) {
        graph.removeFolder(uri)
        _access.value = graph.access()
    }

    fun resume() {
        viewModelScope.launch { IndexScheduler.resumeByUser(app) }
    }

    fun pause() {
        viewModelScope.launch { IndexScheduler.pauseByUser(app) }
    }

    fun loadSkipped() {
        viewModelScope.launch { _skipped.value = retriever.skipped(50) }
    }

    fun retrySkipped() {
        viewModelScope.launch {
            retriever.retrySkipped()
            IndexScheduler.indexNow(app)
            _skipped.value = emptyList()
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
    val openBatterySettings: () -> Unit = {},
    val chooseMorePhotos: () -> Unit = {},
    val allowAllFiles: () -> Unit = {},
    val addFolder: () -> Unit = {},
    val removeFolder: (String) -> Unit = {},
    /** Null hides the link, for a host that has nowhere to go. */
    val openSettings: (() -> Unit)? = null,
)

/** Why a text-only phone reads less, in its own numbers. */
internal fun textOnlyNote(ramGb: Double): String =
    "This phone has %.0f GB of memory, too little to understand photos or meaning, so Retriever finds files by their names and the words inside them.".format(ramGb)

@Composable
fun StatusScreen(
    onBack: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    vm: StatusViewModel = appViewModel { StatusViewModel(it) },
) {
    val context = LocalContext.current
    val counts by vm.counts.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val index by vm.index.collectAsStateWithLifecycle()
    val skippedFiles by vm.skipped.collectAsStateWithLifecycle()
    val access by vm.access.collectAsStateWithLifecycle()
    val folderError by vm.folderError.collectAsStateWithLifecycle()
    var skippedOpen by rememberSaveable { mutableStateOf(false) }
    val power = remember { context.getSystemService(PowerManager::class.java) }
    var unrestricted by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        unrestricted = power.isIgnoringBatteryOptimizations(context.packageName)
        if (skippedOpen) vm.loadSkipped()
        vm.refreshAccess()
    }
    // Asking again on Android 14 opens the picker, so the person can add to the photos they chose.
    val activity = LocalActivity.current
    val photosLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        openSettingsIfBlocked(activity, it)
        vm.photosPicked()
    }
    val folderLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::addFolder) }
    StatusContent(
        counts = counts,
        prefs = prefs,
        index = index,
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
            openBatterySettings = { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
            chooseMorePhotos = { photosLauncher.launch(Access.mediaPermissions(Build.VERSION.SDK_INT).toTypedArray()) },
            allowAllFiles = { openAllFilesSettings(context) },
            addFolder = { folderLauncher.launch(null) },
            removeFolder = vm::removeFolder,
            openSettings = onOpenSettings,
        ),
        access = access,
        textOnly = vm.textOnly,
        folderError = folderError,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusContent(
    counts: Store.Counts,
    prefs: Prefs,
    index: IndexState,
    skippedFiles: List<Store.StoredFile>,
    skippedOpen: Boolean,
    batteryHint: Boolean,
    actions: StatusActions = StatusActions(),
    access: Access = Access.ALL,
    textOnly: Boolean = false,
    folderError: String? = null,
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

                if (counts.tailFiles > 0 && !textOnly) {
                    Muted(
                        if (counts.tailFiles == 1L) "1 long file still being read in depth"
                        else "${Format.count(counts.tailFiles)} long files still being read in depth",
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
                Coverage(access, folderError, actions)

                actions.openSettings?.let { open ->
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    ListItem(
                        headlineContent = { Text("Settings") },
                        supportingContent = { Text("What to read, storage and more") },
                        leadingContent = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                        modifier = Modifier.clickable(role = Role.Button, onClick = open),
                    )
                }
            }
        }
    }
}

/** What Retriever can see, folders the person added, and what it can't see with a way to fix it. */
@Composable
private fun Coverage(access: Access, folderError: String?, actions: StatusActions) {
    Header("What Retriever can see")
    val (gaps, covered) = access.coverage().partition { it.gap }
    for (line in covered) {
        ListItem(
            headlineContent = { Text(line.text) },
            leadingContent = { Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = if (line.fix == Fix.CHOOSE_PHOTOS) {
                { TextButton(onClick = actions.chooseMorePhotos) { Text("Choose more photos") } }
            } else {
                null
            },
        )
    }
    if (access.allFiles) return
    if (access.folders.isNotEmpty()) {
        Text(
            "Folders you added",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
        for (folder in access.folders) {
            ListItem(
                headlineContent = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingContent = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                trailingContent = {
                    IconButton(onClick = { actions.removeFolder(folder.uri) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove ${folder.name}")
                    }
                },
            )
        }
    }
    TextButton(onClick = actions.addFolder, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Add a folder") }
    folderError?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    for (line in gaps) {
        ListItem(
            headlineContent = { Text(line.text) },
            leadingContent = { Icon(Icons.Outlined.Block, contentDescription = null) },
            // The same request as Choose more photos; Settings when Android no longer asks.
            trailingContent = if (line.fix == Fix.ALLOW_MEDIA) {
                { TextButton(onClick = actions.chooseMorePhotos) { Text("Allow") } }
            } else {
                null
            },
        )
    }
    Button(onClick = actions.allowAllFiles, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Allow access to all files") }
}

@Composable
internal fun Muted(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
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
internal fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
