package app.retriever.status

import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
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
import app.retriever.core.IndexState
import app.retriever.core.Prefs
import app.retriever.core.ReadingSchedule
import app.retriever.core.ReadingStatus
import app.retriever.core.SourceFile
import app.retriever.core.StatusAction
import app.retriever.core.Store
import app.retriever.core.Words
import app.retriever.core.motion.MascotMood
import app.retriever.home.Celebration
import app.retriever.home.rememberCelebrating
import app.retriever.indexing.openBatterySettings
import app.retriever.motion.Mascot
import app.retriever.nav.Route
import app.retriever.ui.components.CountBar
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
    val readNow: () -> Unit = {},
    val stopReadNow: () -> Unit = {},
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
    val requestReading = app.retriever.indexing.rememberReadingPermission()
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
            readNow = { requestReading { vm.readNow() } },
            stopReadNow = vm::stopReadNow,
            toggleSkipped = {
                skippedOpen = !skippedOpen
                if (skippedOpen) vm.loadSkipped()
            },
            retrySkipped = vm::retrySkipped,
            openBatterySettings = { openBatterySettings(context) },
            chooseMorePhotos = { photosLauncher.launch(Access.mediaPermissions(Build.VERSION.SDK_INT).toTypedArray()) },
            allowAllFiles = { openAllFilesSettings(context) },
            addFolder = { folderLauncher.launch(null) },
            removeFolder = vm::removeFolder,
            openSettings = onOpenSettings,
        ),
        access = ui.access,
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
    readingStatus: ReadingStatus?,
    modifier: Modifier = Modifier,
    actions: StatusActions = StatusActions(),
    access: Access = Access.ALL,
    folderError: String? = null,
    error: String? = null,
    celebration: Celebration = Celebration(),
) {
    val progress = counts?.progress
    val skipped = progress?.couldntRead ?: 0
    val unfinished = progress?.unfinished == true
    val failure = error ?: index.lastError

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(Words.READING_STATUS)
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
                    // Reading is this screen's subject: the dog walks for as long as it runs, not the shared walk-and-rest.
                    if (mood != null) Mascot(mood, Modifier.align(Alignment.CenterHorizontally).width(160.dp), settles = !index.running)
                    // Only the state is announced, not each file: the count is in the detail, the bar is the same numbers.
                    val title = readingStatus?.title ?: "Checking your files…"
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = title.substringBefore(" · ")
                        },
                    )
                    // One reason at a time: a failure outranks the schedule's own wait, which follows from the title.
                    if (failure != null) {
                        Text(failure, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    } else {
                        readingStatus?.detail?.let { Muted(it) }
                    }
                    readingStatus?.count?.let { Text(it, style = MaterialTheme.typography.bodyLarge.tabular()) }
                    val done = readingStatus?.done
                    val total = readingStatus?.total
                    if (done != null && total != null) CountBar(done, total, Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (readingStatus?.action) {
                            StatusAction.RESUME -> Button(onClick = actions.resume) { Text(StatusAction.RESUME.label) }
                            StatusAction.READ_FOR_15 -> Button(onClick = actions.readNow) { Text(Words.READ_FOR_15) }
                            StatusAction.STOP -> OutlinedButton(onClick = actions.stopReadNow) { Text(StatusAction.STOP.label) }
                            else -> Unit
                        }
                        if (readingStatus?.offersPause(counts.progress) == true) OutlinedButton(onClick = actions.pause) { Text(Words.PAUSE) }
                    }
                    // Laid out from the batch's start, before core names its first file, so the row never blinks in.
                    if (readingStatus?.active == true && (index.running || index.currentFile != null)) ReadingNow(index.currentFile, size = 48.dp)
                    // What was read stays on show through a pause or a wait; only the sentence above changes.
                    if (index.recentFiles.isNotEmpty()) RecentlyRead(index.recentFiles, index.running, actions.openFile)
                }

                Header(Words.DETAILS)
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (row in Words.breakdown(counts.progress)) {
                        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(row.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Text(row.count, style = MaterialTheme.typography.bodyLarge.tabular())
                        }
                    }
                }

                if (skipped > 0) {
                    ListItem(
                        headlineContent = {
                            Text(Words.couldntProcess(skipped))
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

                // The OS exemption is a different remedy from the schedule's own wait, but not beside a failure or the person's Pause.
                if (batteryHint && (progress?.scheduled ?: 0) > 0 && failure == null && !prefs.paused) {
                    ListItem(
                        headlineContent = { Text("Let Retriever process files while you’re not using it") },
                        supportingContent = {
                            Text(
                                "Your phone may stop it to save battery." +
                                    if (prefs.automaticReading == ReadingSchedule.CHARGING) " It still processes files only while charging." else "",
                            )
                        },
                        leadingContent = { Icon(painterResource(R.drawable.ic_battery_alert), contentDescription = null) },
                        modifier = Modifier.clickable(onClick = actions.openBatterySettings),
                    )
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Coverage(access, folderError, actions)

                actions.openSettings?.let { open ->
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    ListItem(
                        headlineContent = { Text("Settings") },
                        supportingContent = { Text("What to process, storage and more") },
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
    Header(Words.WHAT_IT_CAN_SEE)
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
