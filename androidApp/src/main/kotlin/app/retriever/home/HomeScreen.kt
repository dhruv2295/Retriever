package app.retriever.home

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retriever.R
import app.retriever.access.openAllFilesSettings
import app.retriever.core.DescriptionState
import app.retriever.core.DeviceWords
import app.retriever.core.DownloadState
import app.retriever.core.Kind
import app.retriever.core.ReadingPhase
import app.retriever.core.ReadingPolicy
import app.retriever.core.ReadingStatus
import app.retriever.core.Retriever
import app.retriever.core.SamePerson
import app.retriever.core.StatusAction
import app.retriever.core.UpgradePolicy
import app.retriever.core.UpgradeStage
import app.retriever.core.Words
import app.retriever.core.motion.MascotMood
import app.retriever.model.FreeUpSpaceButton
import app.retriever.model.MobileDataAsk
import app.retriever.model.ModelState
import app.retriever.model.UpgradeActions
import app.retriever.model.UpgradeCard
import app.retriever.model.UpgradeFlow
import app.retriever.model.UpgradeUi
import app.retriever.model.actions
import app.retriever.model.description
import app.retriever.model.downloadState
import app.retriever.motion.Mascot
import app.retriever.nav.Route
import app.retriever.nav.SetupRoute
import app.retriever.nav.StatusRoute
import app.retriever.nav.route
import app.retriever.search.SearchActions
import app.retriever.setup.CommitStage
import app.retriever.ui.Format
import app.retriever.ui.components.CountBar
import app.retriever.ui.components.contentPaddingFor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

// Home: where you start looking for something, with one calm line on how well Retriever knows the phone so far.
// Order: title, status line, Starred, Recently added, People (or its offer), On this day, Browse, the optional download,
// then Free up space.
// Colour tokens only.

private val FullLine = StaggeredGridItemSpan.FullLine

/** What Home's status line and its sheet can ask for; every default does nothing, so a test passes only what it checks. */
class HomeActions(
    val resume: () -> Unit = {},
    val allowAccess: () -> Unit = {},
    val readNow: () -> Unit = {},
    val stopReadNow: () -> Unit = {},
    val dismissCaughtUp: () -> Unit = {},
    val commit: (whileCharging: Boolean) -> Unit = {},
    val hideOnThisDay: () -> Unit = {},
    val answerSamePerson: (SamePerson, same: Boolean) -> Unit = { _, _ -> },
    val turnOnPeople: () -> Unit = {},
    val dismissPeopleOffer: () -> Unit = {},
    /** The one-time card's Got it, and its Notify me: null when there is nothing to ask. */
    val dismissIntro: () -> Unit = {},
    val notify: (() -> Unit)? = null,
)

@Composable
fun HomeScreen(
    actions: SearchActions,
    state: LazyStaggeredGridState,
    padding: PaddingValues,
    side: Dp,
    snackbar: SnackbarHostState,
    /** The places a navigation rail shows; Browse leaves those kinds out. */
    rail: List<Route> = emptyList(),
    vm: HomeViewModel = koinViewModel(),
    upgrade: UpgradeFlow = koinInject(),
    retriever: Retriever = koinInject(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val running by vm.indexRunning.collectAsStateWithLifecycle()
    val up by upgrade.ui.collectAsStateWithLifecycle()
    val prefs by retriever.prefs.collectAsStateWithLifecycle()
    val upgradeActions = remember(upgrade) { upgrade.actions(dismissible = true) }
    val requestReading = app.retriever.indexing.rememberReadingPermission()
    val askBattery = app.retriever.indexing.rememberBatteryAsk()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Home is usable once its counts load: reportFullyDrawn, so a cold start's time to full display is measurable.
    ReportDrawnWhen { ui.counts != null }
    // The card's Notify me asks core for the one notice, and Android for notifications where they are off.
    val notifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    // All-files access is switched in Settings, so the "can't see your files" line clears on return without a restart.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
    // Built once (again when Notify me has been asked): a fresh HomeActions per recomposition would defeat HomeBody's
    // skipping. Not keyed on vm: it lives as long as this screen's back-stack entry.
    val homeActions = remember(context, requestReading, askBattery, prefs.notifyWhenCaughtUp, snackbar, scope) {
        HomeActions(
            resume = vm::resume,
            allowAccess = { openAllFilesSettings(context) },
            readNow = { requestReading { vm.readNow() } },
            stopReadNow = vm::stopReadNow,
            dismissCaughtUp = vm::dismissCaughtUp,
            commit = { charging ->
                requestReading {
                    vm.commit(charging)
                    askBattery()
                }
            },
            hideOnThisDay = {
                vm.hideOnThisDay()
                scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    if (snackbar.showSnackbar("On this day hidden", "Undo", duration = SnackbarDuration.Short) ==
                        SnackbarResult.ActionPerformed
                    ) {
                        vm.showOnThisDay()
                    }
                }
            },
            answerSamePerson = vm::answerSamePerson,
            turnOnPeople = vm::turnOnPeople,
            dismissPeopleOffer = vm::dismissPeopleOffer,
            dismissIntro = vm::dismissIntro,
            // Hidden once asked: the card has nothing more to promise.
            notify = if (prefs.notifyWhenCaughtUp) {
                null
            } else {
                {
                    vm.requestCaughtUpNotice()
                    if (Build.VERSION.SDK_INT >= 33 && !context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
                        notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            },
        )
    }
    HomeBody(
        ui, running, actions, state, padding, side,
        homeActions,
        vm.celebration,
        upgrade = up, upgradeActions = upgradeActions, offer = UpgradePolicy.showOnHome(up.stage, prefs),
        rail = rail,
    )
}

/**
 * The caught-up pop: true from the first time this is on screen with [enabled] and the celebration due, until it
 * leaves composition. The haptic comes 0.2 s after the dog appears; only then is it marked spent, so a screen left
 * in that moment gets it next time.
 */
@Composable
internal fun rememberCelebrating(enabled: Boolean, celebration: Celebration): Boolean {
    var celebrating by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(enabled, celebration) {
        if (enabled && !celebrating && celebration.due()) {
            celebrating = true
            delay(200)
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            celebration.done()
        }
    }
    return celebrating
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeBody(
    ui: HomeUi,
    indexRunning: Boolean,
    actions: SearchActions,
    state: LazyStaggeredGridState,
    padding: PaddingValues,
    side: Dp,
    home: HomeActions = HomeActions(),
    celebration: Celebration = Celebration(),
    /** The one upgrade: its download is the status line; its offer, when [offer], the last row. */
    upgrade: UpgradeUi = UpgradeUi(),
    upgradeActions: UpgradeActions = UpgradeActions(),
    offer: Boolean = false,
    rail: List<Route> = emptyList(),
) {
    val context = LocalContext.current
    val download = remember(upgrade, context) { upgrade.downloadState(Format.downloadSize(context, upgrade.sizeBytes)) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    fun onAction(action: StatusAction, phase: ReadingPhase) {
        when (action) {
            StatusAction.CONTINUE -> actions.onNavigate(SetupRoute)
            StatusAction.ALLOW_ACCESS -> home.allowAccess()
            StatusAction.CHOOSE_WHEN -> choosing = true
            StatusAction.READ_FOR_15 -> home.readNow()
            StatusAction.STOP -> if (phase == ReadingPhase.DOWNLOADING) upgradeActions.cancel() else home.stopReadNow()
            StatusAction.RESUME -> home.resume()
            StatusAction.DISMISS -> home.dismissCaughtUp()
            StatusAction.TRY_AGAIN -> if (phase == ReadingPhase.TUNING) upgradeActions.retryTune() else upgradeActions.download()
        }
    }
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(380.dp),
        state = state,
        contentPadding = contentPaddingFor(padding, side = side),
        verticalItemSpacing = 24.dp,
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "title", span = FullLine) {
            Text(
                "Retriever",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
            )
        }
        // Not loaded yet: show nothing rather than flash the first-run message over a full index.
        val counts = ui.counts ?: return@LazyVerticalStaggeredGrid
        val noSpace = (upgrade.model as? ModelState.Failed)?.takeIf { it.noSpace }?.reason
        val status = homeStatus(ui, download, noSpace)
        if (ui.introDue) {
            item(key = "intro", span = FullLine) {
                val state = upgrade.description
                HomeIntroCard(
                    state,
                    ui.planned,
                    ui.progress.rest,
                    Format.downloadSize(context, upgrade.sizeBytes),
                    ui.readsOnBattery,
                    onRecentAction = if (state == DescriptionState.WAITING_FOR_WIFI) upgradeActions.useMobileData else upgradeActions.download,
                    onNotify = home.notify,
                    onGotIt = home.dismissIntro,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        if (counts.values.sum() == 0L && ui.hasAccess) {
            // Still looking while setup runs; "nothing" only once a finished look found nothing.
            item(key = "empty", span = FullLine) { EmptyHome(indexRunning || !ui.libraryChosen) }
            return@LazyVerticalStaggeredGrid
        }
        // The card says the same until Got it.
        if (status != null && !ui.introDue) {
            item(key = "status", span = FullLine) {
                val shown = status.action != StatusAction.DISMISS || !ui.caughtUpDismissed
                if (shown) {
                    val celebrating = rememberCelebrating(status.action == StatusAction.DISMISS, celebration)
                    // On a card like Home's other blocks; Settings keeps it a plain row in its list.
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) {
                        StatusLine(
                            status,
                            when {
                                celebrating -> MascotMood.CAUGHT_UP
                                status.active -> MascotMood.WALKING
                                else -> MascotMood.RESTING
                            },
                            onOpen = { actions.onNavigate(StatusRoute) },
                            onAction = { onAction(it, status.phase) },
                            more = if (noSpace != null && status.phase == ReadingPhase.DOWNLOAD_STOPPED) ({ FreeUpSpaceButton() }) else null,
                        )
                    }
                }
            }
        }
        // The sections wait for every source to answer, then draw together in this order: none lands above another later.
        val facts = ui.home
        if (!ui.sectionsReady || facts == null) return@LazyVerticalStaggeredGrid
        if (facts.starredCount > 0 && facts.starred.isNotEmpty()) {
            val hasAccess = ui.hasAccess
            item(key = "starred", span = FullLine, contentType = "starred") { StarredSection(hasAccess, facts, actions) }
        }
        // Pictures need the files themselves: without access they would only fail to load.
        if (ui.hasAccess && facts.recentlyAdded.isNotEmpty()) {
            item(key = "recent", span = FullLine, contentType = "recent") { RecentlyAddedSection(facts.recentlyAdded, actions) }
        }
        if (ui.hasAccess && (ui.people.isNotEmpty() || ui.facesLeft > 0)) {
            item(key = "people", span = FullLine, contentType = "people") {
                PeopleSection(ui.people, ui.facesLeft, actions.onNavigate, ui.samePeople, home.answerSamePerson)
            }
        } else if (ui.hasAccess && ui.peopleOffer && !ui.introDue && (counts[Kind.PHOTO] ?: 0L) > 0L) {
            // Not beside the one-time card, which already asks for a decision.
            item(key = "people-offer", span = FullLine) { PeopleOffer(home.turnOnPeople, home.dismissPeopleOffer) }
        }
        val day = ui.memoriesDay
        if (ui.hasAccess && ui.showOnThisDay && day != null && ui.memories.isNotEmpty()) {
            item(key = "day", span = FullLine, contentType = "day") { OnThisDaySection(day, ui.memories, actions.onNavigate, home.hideOnThisDay) }
        }
        // Kinds the navigation rail shows aren't repeated here.
        val browse = facts.browse.filter { it.route() !in rail }
        if (browse.isNotEmpty() || facts.folders > 0) {
            item(key = "browse", span = FullLine, contentType = "browse") { BrowseSection(browse, facts.folders, actions.onNavigate) }
        }
        if (offer && upgrade.stage == UpgradeStage.OFFER) {
            item(key = "optional", span = FullLine) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader(Words.OPTIONAL)
                    UpgradeCard(upgrade, upgradeActions, Modifier.padding(horizontal = 16.dp), compact = true)
                }
            }
        }
        if (facts.largeCount > 0 || facts.possibleCopies > 0) {
            item(key = "free", span = FullLine, contentType = "free") { FreeUpSpaceSection(facts, actions.onNavigate) }
        }
    }
    // The status line's Try again and the one-time card ask too; the offer's card, when shown, asks for itself.
    if (!(offer && upgrade.stage == UpgradeStage.OFFER)) MobileDataAsk(upgrade, upgradeActions)
    if (choosing) {
        ModalBottomSheet(onDismissRequest = { choosing = false }) {
            Box(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
                CommitStage(ui.progress.rest, commit = {
                    choosing = false
                    home.commit(it)
                })
            }
        }
    }
}

/**
 * The one status line, on Home and Settings: the dog, what is happening and one plain detail, a thin bar while reading or
 * downloading, and the line's one button ([more] beside it). The whole line opens Reading status ([onOpen]). Only the
 * state, not each file's count, is announced: the title is the live node and holds still while the detail's count moves.
 */
@Composable
internal fun StatusLine(
    status: ReadingStatus,
    mood: MascotMood,
    onOpen: (() -> Unit)?,
    onAction: (StatusAction) -> Unit,
    modifier: Modifier = Modifier,
    more: (@Composable () -> Unit)? = null,
) {
    val done = status.done
    val total = status.total
    val bar = done != null && total != null
    Column(modifier) {
        ListItem(
            leadingContent = { Mascot(mood, Modifier.width(24.dp)) },
            headlineContent = {
                Text(
                    status.title,
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        liveRegion = LiveRegionMode.Polite
                        if (bar) contentDescription = status.title.substringBefore(" · ")
                    },
                )
            },
            supportingContent = if (status.detail != null || status.count != null || bar) {
                {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        status.count?.let { Text(it) }
                        if (done != null && total != null) CountBar(done, total, Modifier.fillMaxWidth())
                        status.detail?.let { Text(it) }
                    }
                }
            } else {
                null
            },
            trailingContent = if (status.action == StatusAction.DISMISS) {
                { IconButton(onClick = { onAction(StatusAction.DISMISS) }) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Dismiss") } }
            } else if (onOpen != null) {
                { Icon(painterResource(R.drawable.ic_keyboard_arrow_right), contentDescription = null) }
            } else {
                null
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = if (onOpen != null) Modifier.clickable(role = Role.Button, onClickLabel = "Show processing details", onClick = onOpen) else Modifier,
        )
        // Spelled out and below the words, so it never squeezes them at large text; aligned with the title.
        status.action?.takeIf { it != StatusAction.DISMISS }?.let { action ->
            FlowRow(Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onAction(action) }) { Text(action.label) }
                more?.invoke()
            }
        }
    }
}

@Composable
private fun EmptyHome(looking: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Mascot(if (looking) MascotMood.WALKING else MascotMood.NO_MATCH, Modifier.width(160.dp))
        Text(if (looking) "Looking for your files" else "Nothing to search yet", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            if (looking) {
                "This takes a minute. You can search by name as soon as they turn up."
            } else {
                "Save a PDF, take a screenshot or a photo, and it'll show up here."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Core's status line, once its live reading status has arrived (its detail carries the live count). [noSpace]: why a
 * download Android refused for space stopped, said in place of core's stopped-download line, which speaks of Wi-Fi.
 */
internal fun homeStatus(ui: HomeUi, download: DownloadState? = null, noSpace: String? = null): ReadingStatus? =
    ReadingPolicy.line(ui.readingStatus, ui.libraryChosen, ui.hasAccess, ui.progress.libraryTotal, DeviceWords.ANDROID, download)?.let {
        if (noSpace != null && it.phase == ReadingPhase.DOWNLOAD_STOPPED) it.copy(title = Words.NO_SPACE, detail = noSpace) else it
    }
