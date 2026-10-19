package app.retriever.settings

import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retriever.BuildConfig
import app.retriever.R
import app.retriever.core.Backend
import app.retriever.core.Kind
import app.retriever.core.ModelBuild
import app.retriever.core.Prefs
import app.retriever.core.ReadingSchedule
import app.retriever.core.Retriever
import app.retriever.diagnostics.ExitProblem
import app.retriever.diagnostics.ExitReasons
import app.retriever.model.ModelState
import app.retriever.model.TuneState
import app.retriever.model.tuneSummary
import app.retriever.status.textOnlyNote
import app.retriever.ui.Format
import app.retriever.ui.components.Header
import app.retriever.ui.components.Muted
import app.retriever.ui.tabular
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel

/** The order Settings lists the "Read these file types" switches in (audio is name only, so it has none); which of them show is core's [app.retriever.core.Retriever.readableKinds]. */
private val SEARCH_INSIDE = listOf(Kind.PDF, Kind.DOC, Kind.SCREENSHOT, Kind.PHOTO, Kind.VIDEO)

private const val NOTICES_ASSET = "THIRD_PARTY_NOTICES.md"

internal const val INCLUDED_FILES_FOOTER =
    "Turning a type off keeps name search and removes its content from search. Excluding a folder removes it from Retriever. Your original files stay where they are."

/** The confirmation for removing the download; [size] is what is on the phone, null when it can't be read. */
internal fun removeDownloadMessage(size: String?): String =
    (if (size != null) "This frees $size. " else "") + "File names and text already read still work. Search by meaning needs the download again."

/** Everything the Settings page shows, so a screenshot test can set it up without a graph. */
data class SettingsUi(
    val prefs: Prefs,
    /** What the model runs on and the file it loaded: the debug section's facts, kept apart from indexing progress. */
    val backend: Backend? = null,
    val detail: String? = null,
    val loadedBuild: ModelBuild? = null,
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
    /** One line for the snackbar: a result or an error. The screen calls messageShown() after showing it. */
    val message: String? = null,
    /** Crashes and ANRs the system recorded; read when Settings opens, so the screen sets it. */
    val problems: List<ExitProblem> = emptyList(),
    /** Whether Android lets Retriever post its reading notification; read when Settings opens and resumes. */
    val notificationsOn: Boolean = true,
)

class SettingsActions(
    val back: () -> Unit = {},
    val setReadingSchedule: (ReadingSchedule) -> Unit = {},
    val readNow: () -> Unit = {},
    val pause: () -> Unit = {},
    val resume: () -> Unit = {},
    val openStatus: () -> Unit = {},
    val setOnThisDay: (Boolean) -> Unit = {},
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
    val shareProblems: () -> Unit = {},
    val openAccess: () -> Unit = {},
    val clearRecentSearches: () -> Unit = {},
    val openNotificationSettings: () -> Unit = {},
)

@Composable
fun SettingsScreen(onBack: () -> Unit, onStatus: () -> Unit = {}, vm: SettingsViewModel = koinViewModel()) {
    val requestReading = app.retriever.indexing.rememberReadingPermission()
    val state by vm.uiState.collectAsStateWithLifecycle()
    var confirmFree by rememberSaveable { mutableStateOf(false) }
    var showNotices by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    // Shown first, cleared after: clearing first would change the key and dismiss the snackbar at once.
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }
    val context = LocalContext.current
    // Read again on return: the person may have changed it in the system's settings page.
    var notificationsOn by remember { mutableStateOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.refreshStorage()
        notificationsOn = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    }
    // Read from the assets only when asked for: 22 KB nobody opens on most visits.
    val notices by produceState<String?>(null, showNotices) {
        if (showNotices) value = withContext(Dispatchers.IO) { context.assets.open(NOTICES_ASSET).bufferedReader().use { it.readText() } }
    }
    // Diagnostics are for debug builds only.
    val problems by produceState(emptyList()) { if (BuildConfig.DEBUG) value = withContext(Dispatchers.IO) { ExitReasons.read(context) } }
    if (showNotices) {
        BackHandler { showNotices = false }
        NoticesContent(notices) { showNotices = false }
        return
    }
    // Built once: a fresh SettingsActions per recomposition would defeat skipping in everything it feeds.
    // Not keyed on vm: it lives as long as this screen's back-stack entry.
    val actions = remember(onBack, context) {
        SettingsActions(
            back = onBack,
            setReadingSchedule = { schedule -> requestReading { vm.setReadingSchedule(schedule) } },
            readNow = { requestReading { vm.readNow() } },
            pause = vm::pause, resume = vm::resume, openStatus = onStatus, setOnThisDay = vm::setOnThisDay,
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
                vm.freeUpSpace()
            },
            downloadAgain = vm::downloadAgain,
            cancelDownload = vm::cancelDownload,
            openNotices = { showNotices = true },
            // Only the system share sheet: the person picks where it goes.
            openAccess = onStatus,
            clearRecentSearches = vm::clearRecentSearches,
            openNotificationSettings = {
                val page = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                context.startActivity(page)
            },
            shareProblems = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, ExitReasons.shareText(problems))
                context.startActivity(Intent.createChooser(send, null))
            },
        )
    }
    SettingsContent(
        ui = state.copy(confirmFree = confirmFree, problems = problems, notificationsOn = notificationsOn),
        actions = actions,
        snackbar = snackbar,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    ui: SettingsUi,
    modifier: Modifier = Modifier,
    actions: SettingsActions = SettingsActions(),
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val prefs = ui.prefs
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = actions.back) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            // Capped inside the scroll, so the gutters scroll too.
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).wrapContentWidth().widthIn(max = 600.dp).fillMaxWidth().padding(bottom = 24.dp),
            ) {
                Header("Reading")
                var schedulesOpen by rememberSaveable { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text("Automatic reading") },
                    supportingContent = { Text(prefs.automaticReading.title) },
                    modifier = Modifier.clickable(role = Role.Button) { schedulesOpen = true },
                )
                if (schedulesOpen) {
                    AlertDialog(
                        onDismissRequest = { schedulesOpen = false },
                        title = { Text("Automatic reading") },
                        text = {
                            Column(Modifier.selectableGroup()) {
                                ReadingSchedule.entries.forEach { schedule ->
                                    ListItem(
                                        headlineContent = {
                                            Text(schedule.title)
                                        },
                                        leadingContent = { androidx.compose.material3.RadioButton(prefs.automaticReading == schedule, null) },
                                        modifier = Modifier.selectable(prefs.automaticReading == schedule, role = Role.RadioButton) {
                                            actions.setReadingSchedule(schedule)
                                            schedulesOpen =
                                                false
                                        },
                                    )
                                }
                            }
                        },
                        confirmButton = { TextButton(onClick = { schedulesOpen = false }) { Text("Done") } },
                    )
                }
                Muted(
                    when (prefs.automaticReading) {
                        ReadingSchedule.CHARGING -> "Reads while charging when Retriever is open or your screen is off."
                        ReadingSchedule.BATTERY -> "Reads while your phone is available, above 20% battery. Pauses for power saving or heat."
                        ReadingSchedule.MANUAL -> "Choose Read now to start a short reading session."
                    },
                    Modifier.padding(horizontal = 16.dp),
                )
                ListItem(headlineContent = { Text("Read now for 15 minutes") }, modifier = Modifier.clickable(role = Role.Button, onClick = actions.readNow))
                ListItem(
                    headlineContent = { Text(if (prefs.paused) "Resume reading" else "Pause reading") },
                    modifier = Modifier.clickable(role = Role.Button, onClick = if (prefs.paused) actions.resume else actions.pause),
                )
                ListItem(headlineContent = { Text("Reading status") }, modifier = Modifier.clickable(role = Role.Button, onClick = actions.openStatus))
                if (!ui.textOnly && !ui.modelMissing) {
                    var adjust by rememberSaveable { mutableStateOf(false) }
                    ListItem(
                        headlineContent = {
                            Text("Adjust reading for this device")
                        },
                        supportingContent = { Text("Run the setup check again.") },
                        modifier = Modifier.clickable {
                            adjust =
                                !adjust
                        },
                    )
                    if (adjust) TuneRow(ui.tune, actions.tuneAgain)
                }
                var help by rememberSaveable { mutableStateOf(false) }
                ListItem(headlineContent = { Text("How reading works") }, modifier = Modifier.clickable { help = !help })
                if (help) {
                    Muted(
                        "Retriever reads automatically while charging, when its screen is open or your phone’s screen is off. It pauses while you use another app. You can start a short reading session on battery.",
                        Modifier.padding(16.dp),
                    )
                    Muted(
                        "Long files are read in stages. Words already read can match now; later passes add meaning search. Some files reach a reading limit and stay partly read.",
                        Modifier.padding(16.dp),
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Header("Included files")
                ListItem(headlineContent = { Text("Manage access") }, modifier = Modifier.clickable(role = Role.Button, onClick = actions.openAccess))
                Text(
                    "Read these file types",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
                if (ui.textOnly) Muted(textOnlyNote(ui.ramGb), Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                for (kind in SEARCH_INSIDE.filter { it in ui.readable }) {
                    SwitchRow(Format.kindLabelPlural(kind), kind in prefs.kinds) { actions.setSearchInside(kind, it) }
                }
                // No platform reads audio yet, so there is no switch to offer: only the plain fact.
                ListItem(headlineContent = { Text("Audio") }, supportingContent = { Text("Name only on this device") })
                Text(
                    "Excluded folders",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
                if (prefs.excludedFolders.isEmpty()) Muted("None", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                for (folder in prefs.excludedFolders.sorted()) {
                    ListItem(
                        headlineContent = { Text(folder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = {
                            // The visible label repeats on every row, so a screen reader is told which folder.
                            TextButton(
                                onClick = { actions.removeExcluded(folder) },
                                modifier = Modifier.semantics { contentDescription = "Include $folder" },
                            ) { Text("Include folder") }
                        },
                    )
                }
                Muted(INCLUDED_FILES_FOOTER, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Header("Privacy")
                SwitchRow("Save recent searches", prefs.rememberSearches, "Saved on this device. Also used for search shortcuts.", actions.setRememberSearches)
                var confirmClear by rememberSaveable { mutableStateOf(false) }
                ListItem(headlineContent = { Text("Clear recent searches") }, modifier = Modifier.clickable(role = Role.Button) { confirmClear = true })
                if (confirmClear) {
                    AlertDialog(
                        onDismissRequest = { confirmClear = false },
                        title = { Text("Clear recent searches and shortcuts?") },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmClear = false
                                actions.clearRecentSearches()
                            }) { Text("Clear") }
                        },
                        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
                    )
                }
                ListItem(
                    headlineContent = { Text("Reading notifications") },
                    supportingContent = { Text(if (ui.notificationsOn) "On" else "Off") },
                    modifier = Modifier.clickable(role = Role.Button, onClick = actions.openNotificationSettings),
                )

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Header("Storage and downloads")
                Storage(ui, actions)

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Header("Appearance")
                SwitchRow("Show On this day", prefs.showOnThisDay, onChange = actions.setOnThisDay)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
                    if (ui.problems.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Header("Diagnostics (${ui.problems.size})")
                        for (p in ui.problems) {
                            ListItem(
                                headlineContent = { Text(p.kind) },
                                supportingContent = { Text("${DateUtils.getRelativeTimeSpanString(p.atMillis)}. ${p.description}") },
                            )
                        }
                        ListItem(
                            headlineContent = { Text("Share", color = MaterialTheme.colorScheme.primary) },
                            supportingContent = { Text("Nothing is sent unless you pick an app.") },
                            modifier = Modifier.clickable(role = Role.Button, onClick = actions.shareProblems),
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Header("For testing")
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ui.backend?.let { Text(tuneSummary(it, null), style = MaterialTheme.typography.bodyMedium) }
                        ui.detail?.let { Muted(it.replaceFirstChar(Char::uppercase)) }
                        ui.loadedBuild?.let { Muted("Model file: ${it.fileName}") }
                    }
                    SwitchRow("Sample mode: newest $SAMPLE_PER_KIND of each type", prefs.sampleSize != null, onChange = actions.setSampleMode)
                }
            }
        }
    }
    if (ui.confirmFree) {
        AlertDialog(
            onDismissRequest = actions.dismissFreeUpSpace,
            title = { Text("Remove the search download?") },
            text = { Text(removeDownloadMessage(ui.storage?.modelBytes?.let { Format.downloadSize(LocalContext.current, it) })) },
            confirmButton = { TextButton(onClick = actions.freeUpSpace) { Text("Remove download") } },
            dismissButton = { TextButton(onClick = actions.dismissFreeUpSpace) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TuneRow(tune: TuneState, onTune: () -> Unit) {
    ListItem(
        headlineContent = { Text("Run setup check") },
        supportingContent = {
            Text(
                when (tune) {
                    TuneState.Running -> "Tuning… a few minutes at most"
                    is TuneState.Failed -> "Tune failed: ${tune.message}"
                    is TuneState.Done -> "Reading is set up"
                    TuneState.Idle -> "Reading speed adjusts to your phone."
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
private fun ColumnScope.Storage(ui: SettingsUi, actions: SettingsActions) {
    ListItem(headlineContent = { Text("Saved search data · ${ui.storage?.let { Format.bytes(it.indexBytes) } ?: "…"}") })
    if (ui.textOnly) return
    ui.storage?.cacheBytes?.takeIf { it > 0 }?.let { ListItem(headlineContent = { Text("Temporary files · ${Format.bytes(it)}") }) }
    // Present or not, the entry names what the download is for and what it costs; the optional model is the one download.
    val size = (ui.storage?.modelBytes?.takeIf { it > 0 } ?: ui.build?.sizeBytes)?.let { Format.downloadSize(LocalContext.current, it) } ?: "…"
    val d = ui.download
    val busy = d is ModelState.Downloading || d == ModelState.WaitingForWifi || d == ModelState.Checking
    ListItem(
        headlineContent = { Text("Search by meaning and what's in your photos · $size") },
        supportingContent = {
            Text(
                when {
                    d is ModelState.Downloading -> if (d.indeterminate) "Downloading…" else "Downloading, ${(d.progress * 100).toInt()}%"
                    d == ModelState.WaitingForWifi -> "Waiting for Wi-Fi to download"
                    d == ModelState.Checking -> "Checking the download…"
                    ui.modelMissing -> "Not downloaded"
                    else -> "Downloaded"
                },
            )
        },
        trailingContent = if (busy && d != ModelState.Checking) ({ TextButton(onClick = actions.cancelDownload) { Text("Cancel download") } }) else null,
    )
    if (busy) {
        val bar = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        val fraction = (d as? ModelState.Downloading)?.takeUnless { it.indeterminate }?.progress
        if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = bar) else LinearProgressIndicator(bar)
    } else if (ui.modelMissing) {
        ListItem(
            headlineContent = { Text("Download again", color = MaterialTheme.colorScheme.primary) },
            supportingContent = {
                val failed = d as? ModelState.Failed
                Text(
                    failed?.reason ?: "Brings back search by meaning and what's in your photos.",
                    color = if (failed != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            modifier = Modifier.clickable(role = Role.Button, onClick = actions.downloadAgain),
        )
    } else if ((ui.storage?.modelBytes ?: 0) > 0) {
        ListItem(
            headlineContent = { Text("Remove search download", color = MaterialTheme.colorScheme.error) },
            supportingContent = { Text("File names and text already read still work.") },
            modifier = Modifier.clickable(role = Role.Button, onClick = actions.askFreeUpSpace),
        )
    }
    SwitchRow("Use mobile data for downloads", ui.prefs.allowMobileData, "Off: downloads wait for Wi-Fi.", actions.setMobileData)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoticesContent(text: String?, modifier: Modifier = Modifier, onBack: () -> Unit) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Open-source notices") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back") }
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
