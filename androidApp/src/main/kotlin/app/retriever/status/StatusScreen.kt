package app.retriever.status

import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retriever.R
import app.retriever.access.Access
import app.retriever.access.Fix
import app.retriever.access.openAllFilesSettings
import app.retriever.access.openSettingsIfBlocked
import app.retriever.core.DeviceWords
import app.retriever.core.IndexState
import app.retriever.core.Prefs
import app.retriever.core.ReadingPolicy
import app.retriever.core.ReadingStatus
import app.retriever.core.Retriever
import app.retriever.core.SourceFile
import app.retriever.core.StopReason
import app.retriever.core.Store
import app.retriever.core.Words
import app.retriever.core.motion.MascotMood
import app.retriever.home.Celebration
import app.retriever.home.rememberCelebrating
import app.retriever.motion.Mascot
import app.retriever.nav.Route
import app.retriever.ui.Format
import app.retriever.ui.components.Header
import app.retriever.ui.components.Muted
import app.retriever.ui.components.ReadingNow
import app.retriever.ui.components.RecentlyRead
import app.retriever.ui.tabular
import app.retriever.viewer.Opener
import org.koin.androidx.compose.koinViewModel

class StatusActions(
    val back: () -> Unit = {},
    val openFile: (SourceFile) -> Unit = {},
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

@Composable
fun StatusScreen(onBack: () -> Unit, onNavigate: (Route) -> Unit, onOpenSettings: (() -> Unit)? = null, vm: StatusViewModel = koinViewModel()) {
    val context = LocalContext.current
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val recentFiles by rememberUpdatedState(ui.index.recentFiles)
    val openFile = remember(context, onNavigate) {
        { file: SourceFile -> Opener.open(context, file, navigate = onNavigate, siblings = recentFiles) }
    }
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
        counts = ui.counts,
        prefs = ui.prefs,
        index = ui.index,
        skippedFiles = ui.skipped,
        skippedOpen = skippedOpen,
        batteryHint = !unrestricted,
        actions = StatusActions(
            back = onBack,
            openFile = openFile,
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
        access = ui.access,
        textOnly = ui.textOnly,
        folderError = ui.folderError,
        error = ui.error,
        celebration = vm.celebration,
        readingStatus = ui.readingStatus,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusContent(
    counts: Store.Counts?,
    prefs: Prefs,
    index: IndexState,
    skippedFiles: List<Store.StoredFile>,
    skippedOpen: Boolean,
    batteryHint: Boolean,
    modifier: Modifier = Modifier,
    actions: StatusActions = StatusActions(),
    access: Access = Access.ALL,
    textOnly: Boolean = false,
    folderError: String? = null,
    error: String? = null,
    celebration: Celebration = Celebration(),
    readingStatus: ReadingStatus? = null,
) {
    val progress = counts?.progress
    val contentRead = progress?.contentRead ?: 0
    val waiting = progress?.scheduled ?: 0
    val skipped = progress?.couldntRead ?: 0
    val total = progress?.libraryTotal ?: 0
    val unfinished = progress?.unfinished == true
    val failure = error ?: index.lastError
    val status =
        readingStatus
            ?: progress?.let {
                ReadingPolicy.decide(it, if (prefs.paused) StopReason.PAUSED else index.lastStop, index.running, index.currentFile != null, DeviceWords.ANDROID)
            }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Reading status",
                    )
                },
                navigationIcon = {
                    IconButton(onClick = actions.back) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        if (counts == null) {
            LinearProgressIndicator(Modifier.padding(padding).fillMaxWidth().padding(16.dp))
            return@Scaffold
        }
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            // Capped inside the scroll, so the gutters scroll too.
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).wrapContentWidth().widthIn(max = 600.dp).fillMaxWidth().padding(bottom = 24.dp),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The short read being done is not the first full read: counts.waiting is the part nobody chose yet.
                    val celebrating = rememberCelebrating(!unfinished && counts.waiting == 0L && !prefs.paused && !index.running, celebration)
                    val mood = when {
                        index.running -> MascotMood.WALKING
                        celebrating -> MascotMood.CAUGHT_UP
                        unfinished -> MascotMood.RESTING
                        else -> null
                    }
                    if (mood != null) Mascot(mood, Modifier.align(Alignment.CenterHorizontally).width(160.dp))
                    Text(status?.title ?: "Checking your files…", style = MaterialTheme.typography.headlineSmall)
                    // One reason at a time: a failure outranks the schedule's own wait, which follows from the title.
                    if (failure != null) {
                        Text(failure, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    } else {
                        status?.detail?.let { Muted(it) }
                    }
                    Text(Words.readCount(contentRead, progress?.partlyRead ?: 0), style = MaterialTheme.typography.titleMedium.tabular())
                    Muted("${Format.count(progress?.waiting ?: 0)} waiting to read")
                    if ((progress?.notScheduled ?: 0) > 0) {
                        Muted("${Format.count(waiting)} scheduled · ${Format.count(progress?.notScheduled ?: 0)} not scheduled")
                    }
                    if ((progress?.nameOnly ?: 0) > 0) Muted("${Format.count(progress?.nameOnly ?: 0)} name only")
                    if (status?.active == true && index.currentFile != null) ReadingNow(index.currentFile, size = 56.dp)
                    // What was read stays on show through a pause or a wait; only the sentence above changes.
                    if (index.recentFiles.isNotEmpty()) RecentlyRead(index.recentFiles, actions.openFile)
                    when {
                        prefs.paused -> Button(onClick = actions.resume) { Text("Resume reading") }
                        unfinished -> OutlinedButton(onClick = actions.pause) { Text("Pause reading") }
                    }
                }

                if (skipped > 0) {
                    ListItem(
                        headlineContent = {
                            Text("Couldn’t read ${Words.files(skipped)}")
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(if (skippedOpen) "Hide" else "Review files", color = MaterialTheme.colorScheme.primary)
                                Icon(painterResource(if (skippedOpen) R.drawable.ic_expand_less else R.drawable.ic_expand_more), contentDescription = null)
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
                                    headlineContent = { Text(f.file.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
                                    supportingContent = { Text(f.file.folder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                )
                            }
                            TextButton(onClick = actions.retrySkipped, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Try again") }
                        }
                    }
                }

                if ((progress?.meaningWaiting ?: 0) > 0) {
                    Muted("${Format.count(progress?.meaningWaiting ?: 0)} read for words · search by meaning to come", Modifier.padding(16.dp))
                }

                if ((progress?.laterPasses ?: 0) > 0) {
                    Muted("${Format.count(progress?.laterPasses ?: 0)} partly read · more meaning search to come", Modifier.padding(16.dp))
                }

                // The OS exemption is a different remedy from the schedule's own wait, but not beside a failure or the person's Pause.
                if (batteryHint && waiting > 0 && failure == null && !prefs.paused) {
                    ListItem(
                        headlineContent = { Text("Let Retriever keep reading in the background") },
                        supportingContent = { Text("Some phones stop apps working in the background.") },
                        leadingContent = { Icon(painterResource(R.drawable.ic_battery_alert), contentDescription = null) },
                        modifier = Modifier.clickable(onClick = actions.openBatterySettings),
                    )
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Coverage(access, folderError, actions)

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                actions.openSettings?.let { open ->
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    ListItem(
                        headlineContent = { Text("Settings") },
                        supportingContent = { Text("What to read, storage and more") },
                        leadingContent = { Icon(painterResource(R.drawable.ic_settings), contentDescription = null) },
                        modifier = Modifier.clickable(role = Role.Button, onClick = open),
                    )
                }
            }
        }
    }
}

/** What Retriever can see, folders the person added, and what it can't see with a way to fix it. */
@Composable
private fun ColumnScope.Coverage(access: Access, folderError: String?, actions: StatusActions) {
    Header("What Retriever can see")
    val (gaps, covered) = access.coverage().partition { it.gap }
    for (line in covered) {
        ListItem(
            headlineContent = { Text(line.text) },
            leadingContent = {
                Icon(painterResource(R.drawable.ic_check_circle_outlined), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            },
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
                leadingContent = { Icon(painterResource(R.drawable.ic_folder), contentDescription = null) },
                trailingContent = {
                    IconButton(onClick = { actions.removeFolder(folder.uri) }) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = "Remove ${folder.name}")
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
            leadingContent = { Icon(painterResource(R.drawable.ic_block), contentDescription = null) },
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
