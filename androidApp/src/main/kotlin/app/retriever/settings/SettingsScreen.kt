package app.retriever.settings

import android.content.Intent
import android.os.Build
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

/** The order Settings lists the "Read inside" switches in; which of them show is core's [app.retriever.core.Retriever.readableKinds]. */
private val SEARCH_INSIDE = listOf(Kind.PDF, Kind.DOC, Kind.SCREENSHOT, Kind.PHOTO, Kind.AUDIO, Kind.VIDEO)

private const val NOTICES_ASSET = "THIRD_PARTY_NOTICES.md"

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
    val shareProblems: () -> Unit = {},
)

@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = koinViewModel()) {
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
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshStorage() }
    // Read from the assets only when asked for: 22 KB nobody opens on most visits.
    val context = LocalContext.current
    val notices by produceState<String?>(null, showNotices) {
        if (showNotices) value = withContext(Dispatchers.IO) { context.assets.open(NOTICES_ASSET).bufferedReader().use { it.readText() } }
    }
    val problems by produceState(emptyList()) { value = withContext(Dispatchers.IO) { ExitReasons.read(context) } }
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
            shareProblems = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, ExitReasons.shareText(problems))
                context.startActivity(Intent.createChooser(send, null))
            },
        )
    }
    SettingsContent(
        ui = state.copy(confirmFree = confirmFree, problems = problems),
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
                                    Icon(painterResource(R.drawable.ic_close), contentDescription = "Search $folder again")
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
                if (ui.problems.isNotEmpty()) {
                    Header("Recent problems (${ui.problems.size})")
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

                // Unreachable in release. Compose lifts some lambdas out of the branch, so only R8 drops their strings.
                if (BuildConfig.DEBUG) {
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
            title = { Text("Remove the search model?") },
            text = {
                val size = ui.build?.let { " (about ${Format.downloadSize(LocalContext.current, it.sizeBytes)})" }.orEmpty()
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
private fun ColumnScope.Storage(ui: SettingsUi, actions: SettingsActions) {
    ValueRow("Search index", ui.storage?.let { Format.bytes(it.indexBytes) } ?: "…")
    if (ui.textOnly) return
    ValueRow("Search model", ui.storage?.let { if (it.modelBytes > 0) Format.downloadSize(LocalContext.current, it.modelBytes) else "Not downloaded" } ?: "…")
    ui.storage?.cacheBytes?.takeIf { it > 0 }?.let { ValueRow("Temporary files", Format.bytes(it)) }
    val sizeNote = ui.build?.let { "About ${Format.downloadSize(LocalContext.current, it.sizeBytes)}. " }.orEmpty()
    when (val d = ui.download) {
        is ModelState.Downloading, ModelState.WaitingForWifi, ModelState.Checking -> {
            ListItem(
                headlineContent = {
                    Text(
                        when (d) {
                            is ModelState.Downloading ->
                                if (d.indeterminate) "Downloading the search model" else "Downloading the search model, ${(d.progress * 100).toInt()}%"

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
