package app.retriever.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retriever.R
import app.retriever.access.openAllFilesSettings
import app.retriever.core.DeviceWords
import app.retriever.core.HomePhase
import app.retriever.core.HomeStatus
import app.retriever.core.IndexState
import app.retriever.core.ReadingPolicy
import app.retriever.core.Retriever
import app.retriever.core.StopReason
import app.retriever.core.UpgradePolicy
import app.retriever.core.Words
import app.retriever.core.motion.MascotMood
import app.retriever.model.UpgradeActions
import app.retriever.model.UpgradeCard
import app.retriever.model.UpgradeFlow
import app.retriever.model.UpgradeUi
import app.retriever.model.actions
import app.retriever.motion.Mascot
import app.retriever.nav.BrowseRoute
import app.retriever.nav.DuplicatesRoute
import app.retriever.nav.Route
import app.retriever.nav.SetupRoute
import app.retriever.nav.StatusRoute
import app.retriever.search.SearchActions
import app.retriever.setup.CommitStage
import app.retriever.setup.SetupActions
import app.retriever.setup.SetupUi
import app.retriever.ui.Format
import app.retriever.ui.components.contentPaddingFor
import app.retriever.ui.tabular
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

// Home: what to look for, whether search is complete, what is new, where everything is. Colour tokens only, so
// wallpaper colours and the warm palette both work. Spec: the Home design in the wave 5 notes.

private val transparent @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)

private val FullLine = StaggeredGridItemSpan.FullLine

/** Home with its own state holder; [recent] searches are the search screen's. */
@Composable
fun HomeScreen(
    recent: List<String>,
    actions: SearchActions,
    state: LazyStaggeredGridState,
    padding: PaddingValues,
    side: Dp,
    fill: (String, Boolean) -> Unit,
    vm: HomeViewModel = koinViewModel(),
    upgrade: UpgradeFlow = koinInject(),
    retriever: Retriever = koinInject(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val index by vm.indexState.collectAsStateWithLifecycle()
    val up by upgrade.ui.collectAsStateWithLifecycle()
    val prefs by retriever.prefs.collectAsStateWithLifecycle()
    val upgradeActions = remember(upgrade) { upgrade.actions(dismissible = true) }
    val requestReading = app.retriever.indexing.rememberReadingPermission()
    val context = LocalContext.current
    val onResume = remember(vm) { vm::onResume }
    // All-files access is switched in Settings, so the "can't see your files" card clears on return without a restart.
    LifecycleResumeEffect(Unit) {
        onResume()
        onPauseOrDispose {}
    }
    HomeBody(
        ui, index, rememberCharging(), recent, actions, vm::resume, {
            openAllFilesSettings(context)
        }, state, padding, side, fill, vm::hideOnThisDay, { charging ->
            requestReading { vm.commit(charging) }
        }, vm.celebration, vm::dismissCaughtUp, vm::dismissNameSearchHint, vm::dismissScheduleOffer, vm::decideLater,
        upgrade = up.takeIf { UpgradePolicy.showOnHome(it.stage, prefs) }, upgradeActions = upgradeActions,
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

/** Whether the phone is on a charger, from the sticky battery broadcast, kept current while shown. */
@Composable
private fun rememberCharging(): Boolean {
    val context = LocalContext.current
    var charging by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            }
        }
        // The sticky value arrives at once through the return value, and again to the receiver.
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { receiver.onReceive(context, it) }
        onDispose { context.unregisterReceiver(receiver) }
    }
    return charging
}

/** [fill] puts a past search in the field and opens the bar; true also runs it. */
@Composable
internal fun HomeBody(
    ui: HomeUi,
    index: IndexState,
    charging: Boolean,
    recent: List<String>,
    actions: SearchActions,
    onResume: () -> Unit,
    onAllowAccess: () -> Unit,
    state: LazyStaggeredGridState,
    padding: PaddingValues,
    side: Dp,
    fill: (String, Boolean) -> Unit,
    onHideOnThisDay: () -> Unit = {},
    onCommit: (whileCharging: Boolean) -> Unit = {},
    celebration: Celebration = Celebration(),
    onDismissCaughtUp: () -> Unit = {},
    onDismissNameHint: () -> Unit = {},
    onDismissOffer: () -> Unit = {},
    onDecideLater: () -> Unit = {},
    /** The upgrade's compact row, when core says Home shows it. */
    upgrade: UpgradeUi? = null,
    upgradeActions: UpgradeActions = UpgradeActions(),
) {
    val counts = ui.counts
    // Remembered so an unchanged history is the same list and the chip row skips recomposing.
    val chips = remember(recent) { recent.take(5) }
    val tries = remember(recent, ui.suggestions) { if (recent.isEmpty()) ui.suggestions else emptyList() }
    val tiles = remember(ui.kindTotals, ui.folderTotals, ui.pending, ui.waiting, ui.inside) { moreTiles(ui) }
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(380.dp),
        state = state,
        contentPadding = contentPaddingFor(padding, side = side),
        verticalItemSpacing = 24.dp,
        modifier = Modifier.fillMaxSize(),
    ) {
        // Not loaded yet: show nothing rather than flash the first-run message over a full index.
        if (counts == null) return@LazyVerticalStaggeredGrid
        val total = counts.values.sum()
        if (total == 0L) {
            // Still looking while setup runs; "nothing" only once a finished look found nothing.
            val looking = index.running || !ui.libraryChosen
            if (ui.hasAccess) {
                item(key = "empty", span = FullLine) {
                    EmptyHome(looking)
                }
            } else {
                item(key = "status", span = FullLine) { Status(ui, index, charging, actions.onNavigate, onResume, onAllowAccess, celebration) }
            }
            return@LazyVerticalStaggeredGrid
        }
        item(key = "hero", span = FullLine) {
            LibraryHero(ui) {
                Status(ui, index, charging, actions.onNavigate, onResume, onAllowAccess, celebration, onDismissCaughtUp)
            }
        }
        if (!ui.nameSearchHintDismissed) {
            item(key = "name-hint", span = FullLine) {
                ListItem(headlineContent = { Text(Words.NAME_SEARCH_HINT) }, trailingContent = {
                    IconButton(onClick = onDismissNameHint) { Icon(painterResource(R.drawable.ic_close), "Dismiss name search explanation") }
                })
            }
        }
        if (ui.hasAccess && ui.libraryChosen && ui.rest > 0L &&
            !ui.scheduleOfferDismissed
        ) {
            item(key = "commit", span = FullLine) { CommitCard(ui, onCommit, onDismissOffer, onDecideLater) }
        }
        if (upgrade != null) item(key = "upgrade", span = FullLine) { UpgradeCard(upgrade, upgradeActions, Modifier.padding(horizontal = 16.dp)) }
        val home = ui.home
        if (chips.isNotEmpty() || tries.isNotEmpty()) item(key = "chips", span = FullLine, contentType = "chips") { ChipRow(chips, tries, fill) }
        // Pictures need the files themselves: without access they would only fail to load.
        if (home != null && ui.hasAccess &&
            home.newMedia.isNotEmpty()
        ) {
            item(key = "media", span = FullLine, contentType = "media") { MediaSection(ui, home, actions) }
        }
        if (home != null && ui.hasAccess &&
            home.newShots.isNotEmpty()
        ) {
            item(key = "shots", span = FullLine, contentType = "shots") { ShotsSection(ui, home, actions) }
        }
        if (home != null && home.newDocs.isNotEmpty()) item(key = "docs", span = FullLine, contentType = "docs") { DocsSection(ui, home, actions) }
        // Memories and pictures need the files themselves too; a year with nothing, or the switch off, draws nothing.
        val day = ui.memoriesDay
        if (ui.hasAccess && ui.showOnThisDay && day != null && ui.memories.isNotEmpty()) {
            item(key = "day", contentType = "day") { OnThisDaySection(day, ui.memories, actions.onNavigate, onHideOnThisDay) }
        }
        if (home != null && home.starredCount > 0 &&
            home.starred.isNotEmpty()
        ) {
            item(key = "starred", contentType = "starred") { StarredSection(ui, home, actions) }
        }
        if (tiles.isNotEmpty()) item(key = "more", contentType = "more") { MoreSection(tiles, actions.onNavigate) }
        item(key = "tidy") { TidyUp(ui, actions.onNavigate) }
    }
}

// --- Status ---

@Composable
private fun Status(
    ui: HomeUi,
    index: IndexState,
    charging: Boolean,
    go: (Route) -> Unit,
    onResume: () -> Unit,
    onAllowAccess: () -> Unit,
    celebration: Celebration,
    onDismissCaughtUp: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val status = homeStatus(ui, index)
    // Access first: without it the way back to the files comes before the way back to setup.
    when {
        !ui.hasAccess -> StatusCard(
            colors.tertiaryContainer,
            colors.onTertiaryContainer,
            "Retriever can't see your files",
            body = "Search still works on the ${Format.count(ui.contentRead)} files already read. New files won't show up until you allow access.",
            action = "Allow access" to onAllowAccess,
        )

        status == null -> Unit

        status.phase == HomePhase.SETTING_UP -> StatusCard(colors.surfaceContainer, colors.onSurface, status.title, onClick = {
            go(SetupRoute)
        }, clickLabel = "Finish setup")

        else -> {
            val caughtUp = status.phase == HomePhase.CAUGHT_UP && !ui.caughtUpDismissed
            val celebrating = rememberCelebrating(caughtUp, celebration)
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Show reading details") { go(StatusRoute) }
                    .heightIn(min = 48.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Mascot(
                    when {
                        celebrating -> MascotMood.CAUGHT_UP
                        status.phase == HomePhase.READING -> MascotMood.WALKING
                        else -> MascotMood.RESTING
                    },
                    Modifier.width(40.dp),
                )
                Column(Modifier.weight(1f)) {
                    TitleWithCount(status.title, status.detail, MaterialTheme.typography.bodyMedium)
                    if (caughtUp) {
                        ui.home?.nameOnly?.takeIf { it > 0 }?.let {
                            Text("${Format.count(it)} of them by name only", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                // One remedy at a time: Resume, Dismiss, or the way to Reading status the whole row already is.
                if (ui.paused) {
                    TextButton(onClick = onResume) { Text("Resume reading") }
                } else if (!caughtUp) {
                    Text(Words.READING_STATUS, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                if (caughtUp) {
                    IconButton(onClick = onDismissCaughtUp) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = "Dismiss reading status")
                    }
                }
            }
        }
    }
}

/**
 * "[title] · [count]" on one line where it fits. Only [title] is a live region, so TalkBack says when the state
 * changes but not every tick of the count. It is its own node: merged into the clickable row, the row would become
 * the live region and every count change would be announced again.
 */
@Composable
private fun TitleWithCount(title: String, count: String?, style: TextStyle) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = style, modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite })
        if (count != null) Text("· $count", style = style.tabular())
    }
}

@Composable
private fun StatusCard(
    container: Color,
    content: Color,
    title: String,
    count: String? = null,
    body: String? = null,
    mascot: MascotMood? = null,
    onClick: (() -> Unit)? = null,
    clickLabel: String = "Show reading details",
    action: Pair<String, () -> Unit>? = null,
) {
    Surface(color = container, contentColor = content, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier
                    .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = clickLabel, onClick = onClick) else Modifier)
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (mascot != null) Mascot(mascot, Modifier.width(80.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TitleWithCount(title, count, MaterialTheme.typography.bodyLarge)
                    if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium)
                }
                if (onClick != null) Icon(painterResource(R.drawable.ic_keyboard_arrow_right), contentDescription = null)
            }
            // Filled, not tonal: a tonal button is the same colour as these cards and would vanish.
            if (action != null) Button(onClick = action.second, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) { Text(action.first) }
        }
    }
}

/** The rest of the library waits for a choice: Read everything opens the setup's three answers as a sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommitCard(ui: HomeUi, onCommit: (Boolean) -> Unit, onDismiss: () -> Unit, onDecideLater: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text("Search inside more files") },
        supportingContent = { Text("${Format.count(ui.rest)} files still found by name only") },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { open = true }) { Text("Choose when") }
                IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_close), "Dismiss reading offer") }
            }
        },
    )
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }) {
            Box(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
                CommitStage(
                    SetupUi(moreFiles = ui.rest),
                    SetupActions(commit = {
                        open = false
                        onCommit(it)
                    }, decideLater = {
                        open = false
                        onDecideLater()
                    }),
                )
            }
        }
    }
}

@Composable
private fun EmptyHome(looking: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
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

// --- Tidy up ---

@Composable
private fun TidyUp(ui: HomeUi, go: (Route) -> Unit) {
    Column {
        SectionHeader("Tidy up")
        Card {
            val home = ui.home
            if (home != null && home.largeCount > 0) {
                TidyRow(R.drawable.ic_storage, "${Words.largeFiles(home.largeCount)} · ${Format.bytes(home.largeBytes)}") {
                    go(BrowseRoute(sort = "LARGEST", title = "Large files"))
                }
            }
            TidyRow(R.drawable.ic_content_copy, "Check for exact copies") { go(DuplicatesRoute) }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column { content() }
    }
}

@Composable
private fun TidyRow(@DrawableRes icon: Int, text: String, onClick: () -> Unit) {
    ListItem(
        leadingContent = { Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(text) },
        trailingContent = {
            Icon(painterResource(R.drawable.ic_keyboard_arrow_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        colors = transparent,
        modifier = Modifier.clickable(onClickLabel = "Open", onClick = onClick),
    )
}

/** Core's status row, from the live reading status or, before it arrives, the same decision made here. */
internal fun homeStatus(ui: HomeUi, index: IndexState): HomeStatus? = ReadingPolicy.homeStatus(
    ui.libraryChosen,
    ui.progress.libraryTotal,
    ui.readingStatus
        ?: ReadingPolicy.decide(
            ui.progress,
            if (ui.paused) StopReason.PAUSED else index.lastStop,
            index.running,
            index.currentFile != null,
            DeviceWords.ANDROID,
        ),
)
