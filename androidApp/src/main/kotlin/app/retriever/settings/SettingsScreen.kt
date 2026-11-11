package app.retriever.settings

import android.content.Intent
import android.os.Build
import android.text.format.DateUtils
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retriever.BuildConfig
import app.retriever.R
import app.retriever.core.Backend
import app.retriever.core.DeviceWords
import app.retriever.core.Kind
import app.retriever.core.ModelBuild
import app.retriever.core.Prefs
import app.retriever.core.ReadingSchedule
import app.retriever.core.ReadingStatus
import app.retriever.core.StatusAction
import app.retriever.core.UpgradeStage
import app.retriever.core.Words
import app.retriever.core.motion.MascotMood
import app.retriever.diagnostics.ExitProblem
import app.retriever.diagnostics.ExitReasons
import app.retriever.home.StatusLine
import app.retriever.indexing.openNotificationSettings
import app.retriever.indexing.rememberNotificationPermission
import app.retriever.indexing.rememberProcessingNotificationsEnabled
import app.retriever.model.TuneState
import app.retriever.model.UpgradeActions
import app.retriever.model.UpgradeCard
import app.retriever.model.UpgradeFlow
import app.retriever.model.UpgradeUi
import app.retriever.model.actions
import app.retriever.model.tuneSummary
import app.retriever.ui.Format
import app.retriever.ui.components.Header
import app.retriever.ui.components.Muted
import app.retriever.ui.tabular
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/** The order Settings lists the "Process these file types" switches in (audio is name only, so it has none); which of them show is core's [app.retriever.core.Retriever.readableKinds]. */
private val SEARCH_INSIDE = listOf(Kind.PDF, Kind.DOC, Kind.SCREENSHOT, Kind.PHOTO, Kind.VIDEO)

private const val NOTICES_ASSET = "THIRD_PARTY_NOTICES.md"

internal val INCLUDED_FILES_FOOTER: String get() = Words.strings.includedFilesFooter

/** Everything the Settings page shows, so a screenshot test can set it up without a graph. */
data class SettingsUi(
    val prefs: Prefs,
    /** What the model runs on and the file it loaded: the debug section's facts, kept apart from indexing progress. */
    val backend: Backend? = null,
    val detail: String? = null,
    val loadedBuild: ModelBuild? = null,
    val tune: TuneState = TuneState.Idle,
    val storage: StorageUsage? = null,
    /** The one upgrade, the same flow as Try and Home; the screen fills it in. */
    val upgrade: UpgradeUi = UpgradeUi(),
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
    /** The Reading row's line, as on Home; null until core answers. */
    val readingStatus: ReadingStatus? = null,
)

class SettingsActions(
    /** Null for a top-level place (the navigation rail's), with no Back arrow. */
    val back: (() -> Unit)? = {},
    val setReadingSchedule: (ReadingSchedule) -> Unit = {},
    val readNow: () -> Unit = {},
    val stopReadNow: () -> Unit = {},
    val resume: () -> Unit = {},
    val openStatus: () -> Unit = {},
    val setOnThisDay: (Boolean) -> Unit = {},
    val setSearchInside: (Kind, Boolean) -> Unit = { _, _ -> },
    val removeExcluded: (String) -> Unit = {},
    val tuneAgain: () -> Unit = {},
    val setRememberSearches: (Boolean) -> Unit = {},
    val setRecognizePeople: (Boolean) -> Unit = {},
    val setMobileData: (Boolean) -> Unit = {},
    val setDynamicColor: (Boolean) -> Unit = {},
    val setSampleMode: (Boolean) -> Unit = {},
    val askFreeUpSpace: () -> Unit = {},
    val dismissFreeUpSpace: () -> Unit = {},
    val freeUpSpace: () -> Unit = {},
    val upgrade: UpgradeActions = UpgradeActions(),
    val openNotices: () -> Unit = {},
    val shareProblems: () -> Unit = {},
    val openAccess: () -> Unit = {},
    val clearRecentSearches: () -> Unit = {},
    val setNotifications: (Boolean) -> Unit = {},
)

@Composable
fun SettingsScreen(onBack: (() -> Unit)?, onStatus: () -> Unit = {}, vm: SettingsViewModel = koinViewModel(), upgradeFlow: UpgradeFlow = koinInject()) {
    val requestReading = app.retriever.indexing.rememberReadingPermission()
    val requestNotifications = rememberNotificationPermission()
    val notificationsOn by rememberProcessingNotificationsEnabled()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val readingStatus by vm.readingStatus.collectAsStateWithLifecycle()
    val upgrade by upgradeFlow.ui.collectAsStateWithLifecycle()
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
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshStorage() }
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
    val actions = remember(onBack, context, upgradeFlow, requestNotifications) {
        SettingsActions(
            back = onBack,
            setReadingSchedule = { schedule -> requestReading { vm.setReadingSchedule(schedule) } },
            readNow = { requestReading { vm.readNow() } },
            stopReadNow = vm::stopReadNow, resume = vm::resume, openStatus = onStatus, setOnThisDay = vm::setOnThisDay,
            setSearchInside = vm::setSearchInside,
            removeExcluded = vm::removeExcluded,
            tuneAgain = vm::tuneAgain,
            setRememberSearches = vm::setRememberSearches,
            setRecognizePeople = vm::setRecognizePeople,
            setMobileData = vm::setMobileData,
            setDynamicColor = vm::setDynamicColor,
            setSampleMode = vm::setSampleMode,
            askFreeUpSpace = { confirmFree = true },
            dismissFreeUpSpace = { confirmFree = false },
            freeUpSpace = {
                confirmFree = false
                vm.freeUpSpace()
            },
            // Settings keeps the offer: no Not now.
            upgrade = upgradeFlow.actions(dismissible = false),
            openNotices = { showNotices = true },
            openAccess = onStatus,
            clearRecentSearches = vm::clearRecentSearches,
            setNotifications = { on ->
                if (on) requestNotifications() else openNotificationSettings(context)
            },
            // Only the system share sheet: the person picks where it goes.
            shareProblems = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, ExitReasons.shareText(problems))
                context.startActivity(Intent.createChooser(send, null))
            },
        )
    }
    SettingsContent(
        ui = state.copy(
            confirmFree = confirmFree,
            problems = problems,
            notificationsOn = notificationsOn,
            upgrade = upgrade,
            readingStatus = readingStatus,
        ),
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
                title = { Text(Words.strings.appSettings) },
                navigationIcon = {
                    actions.back?.let { IconButton(onClick = it) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = Words.strings.back) } }
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
                HowItWorks()
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Header(Words.READING_STATUS)
                var schedulesOpen by rememberSaveable { mutableStateOf(false) }
                // The line Home shows, so a tap below (Process for 15 minutes) changes it at once. The whole line opens Processing.
                ui.readingStatus?.let { status ->
                    StatusLine(
                        status.copy(action = status.action.takeIf { it == StatusAction.STOP || it == StatusAction.RESUME }),
                        if (status.active) MascotMood.WALKING else MascotMood.RESTING,
                        onOpen = actions.openStatus,
                        onAction = { if (it == StatusAction.STOP) actions.stopReadNow() else actions.resume() },
                    )
                }
                OpenRow(
                    Words.WHEN_TO_READ,
                    Words.strings.scheduleSummary(
                        prefs.automaticReading.title,
                        when (prefs.automaticReading) {
                            ReadingSchedule.CHARGING -> Words.whileChargingBody(DeviceWords.ANDROID)
                            ReadingSchedule.BATTERY -> Words.onBatteryBody(DeviceWords.ANDROID)
                            ReadingSchedule.MANUAL -> Words.strings.onlyWhenYouChoose
                        },
                    ),
                ) { schedulesOpen = true }
                if (schedulesOpen) {
                    AlertDialog(
                        onDismissRequest = { schedulesOpen = false },
                        title = { Text(Words.WHEN_TO_READ) },
                        text = {
                            Column(Modifier.selectableGroup()) {
                                ReadingSchedule.entries.forEach { schedule ->
                                    ListItem(
                                        headlineContent = { Text(schedule.title) },
                                        leadingContent = { androidx.compose.material3.RadioButton(prefs.automaticReading == schedule, null) },
                                        modifier = Modifier.selectable(prefs.automaticReading == schedule, role = Role.RadioButton) {
                                            actions.setReadingSchedule(schedule)
                                            schedulesOpen = false
                                        },
                                    )
                                }
                            }
                        },
                        confirmButton = { TextButton(onClick = { schedulesOpen = false }) { Text(Words.DONE) } },
                    )
                }
                ActionRow(Words.READ_FOR_15, { Text(Words.readNowBody(DeviceWords.ANDROID)) }, onClick = actions.readNow)
                SwitchRow(
                    Words.strings.showProcessingProgress,
                    ui.notificationsOn,
                    if (ui.notificationsOn) Words.strings.notificationsOn else Words.strings.processingProgressNotice,
                    actions.setNotifications,
                )
                if (!ui.textOnly && !ui.modelMissing) TuneRow(ui.tune, actions.tuneAgain)
                // Text-only phones can't run it, so they have no section for it.
                if (!ui.textOnly) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Header(Words.SEARCH_BY_MEANING)
                    SearchByMeaning(ui, actions)
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Header(Words.strings.includedFiles)
                OpenRow(Words.WHAT_IT_CAN_SEE, onClick = actions.openAccess)
                Text(
                    Words.PROCESS_THESE_KINDS,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
                if (ui.textOnly) Muted(Words.textOnlyNote(ui.ramGb, DeviceWords.ANDROID), Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                for (kind in SEARCH_INSIDE.filter { it in ui.readable }) {
                    SwitchRow(Words.kindLabelPlural(kind), kind in prefs.kinds) { actions.setSearchInside(kind, it) }
                }
                // No platform reads audio yet, so there is no switch to offer: only the plain fact.
                ListItem(headlineContent = { Text(Words.kindLabelPlural(Kind.AUDIO)) }, supportingContent = { Text(Words.FOUND_BY_NAME) })
                Text(
                    Words.FOLDERS_TO_SKIP,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
                if (prefs.excludedFolders.isEmpty()) Muted(Words.strings.noneExcluded, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                for (folder in prefs.excludedFolders.sorted()) {
                    ListItem(
                        headlineContent = { Text(folder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = {
                            // The visible label repeats on every row, so a screen reader is told which folder.
                            TextButton(
                                onClick = { actions.removeExcluded(folder) },
                                modifier = Modifier.semantics { contentDescription = Words.strings.includeNamed(folder) },
                            ) { Text(Words.strings.includeFolder) }
                        },
                    )
                }
                Muted(INCLUDED_FILES_FOOTER, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Header(Words.strings.privacy)
                SwitchRow(Words.strings.saveRecentSearches, prefs.rememberSearches, Words.strings.savedOnDeviceShortcuts, actions.setRememberSearches)
                var confirmClear by rememberSaveable { mutableStateOf(false) }
                ActionRow(Words.strings.clearRecentSearches, destructive = true) { confirmClear = true }
                if (confirmClear) {
                    AlertDialog(
                        onDismissRequest = { confirmClear = false },
                        title = { Text(Words.strings.clearSearchesAndShortcutsAsk) },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmClear = false
                                actions.clearRecentSearches()
                            }) { Text(Words.strings.clear) }
                        },
                        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(Words.CANCEL) } },
                    )
                }
                // Turning People off forgets the names the person gave, so it asks first; turning it on doesn't.
                var confirmForget by rememberSaveable { mutableStateOf(false) }
                SwitchRow(Words.RECOGNIZE_PEOPLE, prefs.recognizePeople, Words.RECOGNIZE_PEOPLE_DETAIL) { on ->
                    if (on) actions.setRecognizePeople(true) else confirmForget = true
                }
                if (confirmForget) {
                    AlertDialog(
                        onDismissRequest = { confirmForget = false },
                        title = { Text(Words.FORGET_PEOPLE_TITLE) },
                        text = { Text(Words.FORGET_PEOPLE_BODY) },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmForget = false
                                actions.setRecognizePeople(false)
                            }) { Text(Words.FORGET) }
                        },
                        dismissButton = { TextButton(onClick = { confirmForget = false }) { Text(Words.CANCEL) } },
                    )
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Header(Words.strings.storage)
                Storage(ui)

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Header(Words.strings.appearance)
                SwitchRow(Words.SHOW_ON_THIS_DAY, prefs.showOnThisDay, onChange = actions.setOnThisDay)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SwitchRow(Words.strings.wallpaperColours, prefs.dynamicColor, Words.strings.wallpaperColoursOff, actions.setDynamicColor)
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Header(Words.strings.about)
                ValueRow(Words.strings.version, BuildConfig.VERSION_NAME)
                OpenRow(Words.strings.openSourceNotices, onClick = actions.openNotices)

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
                        ActionRow("Share", { Text("Nothing is sent unless you pick an app.") }, onClick = actions.shareProblems)
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
            title = { Text(Words.REMOVE_DOWNLOAD_ASK) },
            text = { Text(Words.removeDownloadMessage(ui.storage?.modelBytes?.let { Format.downloadSize(LocalContext.current, it) })) },
            confirmButton = { TextButton(onClick = actions.freeUpSpace) { Text(Words.strings.removeDownloadConfirm) } },
            dismissButton = { TextButton(onClick = actions.dismissFreeUpSpace) { Text(Words.CANCEL) } },
        )
    }
}

/** First on the page: what Retriever does with the files and what never leaves the phone. */
@Composable
private fun HowItWorks() {
    var open by rememberSaveable { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(Words.HOW_IT_WORKS) },
        leadingContent = { Icon(painterResource(R.drawable.ic_lock), contentDescription = null) },
        trailingContent = { Icon(painterResource(if (open) R.drawable.ic_expand_less else R.drawable.ic_expand_more), contentDescription = null) },
        modifier = Modifier.clickable(role = Role.Button) { open = !open }.semantics {
            stateDescription =
                if (open) Words.strings.expanded else Words.strings.collapsed
        },
    )
    if (open) {
        Muted(
            Words.strings.howItWorksBody(DeviceWords.ANDROID),
            Modifier.padding(16.dp),
        )
        Muted(
            Words.strings.howItWorksTimes,
            Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun TuneRow(tune: TuneState, onTune: () -> Unit) {
    ActionRow(
        Words.strings.runSetupCheck,
        {
            Text(
                when (tune) {
                    TuneState.Running -> Words.strings.tuningFewMinutes
                    is TuneState.Failed -> Words.strings.tuneFailedMessage(tune.message)
                    is TuneState.Done -> Words.strings.processingIsSetUp
                    TuneState.Idle -> Words.strings.adjustsProcessingSpeed
                },
                color = if (tune is TuneState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        enabled = tune != TuneState.Running,
        trailing = if (tune == TuneState.Running) {
            { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp) }
        } else {
            null
        },
        onClick = onTune,
    )
}

/** What Retriever keeps on the phone: each a title, then its size. Large files and duplicates are on Home. */
@Composable
private fun Storage(ui: SettingsUi) {
    ListItem(
        headlineContent = { Text(Words.RETRIEVERS_DATA) },
        supportingContent = { Text(Words.keptOn(ui.storage?.let { Format.bytes(it.indexBytes) } ?: Words.strings.sizePending, DeviceWords.ANDROID)) },
    )
    if (ui.textOnly) return
    ui.storage?.cacheBytes?.takeIf { it > 0 }?.let {
        ListItem(headlineContent = { Text(Words.TEMPORARY_FILES) }, supportingContent = { Text(Words.keptOn(Format.bytes(it), DeviceWords.ANDROID)) })
    }
}

/** The optional download: the same upgrade as Try and Home until it is done; then its size and how to remove it. */
@Composable
private fun SearchByMeaning(ui: SettingsUi, actions: SettingsActions) {
    if (ui.upgrade.stage == UpgradeStage.DONE) {
        val size =
            (ui.storage?.modelBytes?.takeIf { it > 0 } ?: ui.build?.sizeBytes)?.let { Format.downloadSize(LocalContext.current, it) }
                ?: Words.strings.sizePending
        ListItem(headlineContent = { Text(Words.UPGRADE_TITLE) }, supportingContent = { Text(Words.downloaded(size)) })
        ActionRow(Words.REMOVE_DOWNLOAD, { Text(Words.NAMES_STILL_WORK) }, destructive = true, onClick = actions.askFreeUpSpace)
    } else {
        UpgradeCard(ui.upgrade, actions.upgrade, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        SwitchRow(Words.strings.useMobileDataForDownloads, ui.prefs.allowMobileData, Words.strings.downloadsWaitForWifiShort, actions.setMobileData)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoticesContent(text: String?, modifier: Modifier = Modifier, onBack: () -> Unit) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(Words.strings.openSourceNotices) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = Words.strings.back) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(text ?: Words.strings.loading, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ValueRow(label: String, value: String) {
    ListItem(headlineContent = { Text(label) }, trailingContent = { Text(value, style = MaterialTheme.typography.bodyMedium.tabular()) })
}

/** Opens something (a choice, a screen, the system's settings): the chevron says there is more behind the row. */
@Composable
private fun OpenRow(label: String, supporting: String? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = { Icon(painterResource(R.drawable.ic_keyboard_arrow_right), contentDescription = null) },
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    )
}

/** Does it at once: the label is the action, in the accent colour, or the error colour when it removes something. */
@Composable
private fun ActionRow(
    label: String,
    supporting: @Composable (() -> Unit)? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) },
        supportingContent = supporting,
        trailingContent = trailing,
        modifier = Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    )
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
