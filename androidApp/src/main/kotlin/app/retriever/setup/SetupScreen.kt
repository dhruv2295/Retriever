package app.retriever.setup

import android.animation.ValueAnimator
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retriever.access.Access
import app.retriever.access.AccessService
import app.retriever.access.openAllFilesSettings
import app.retriever.access.openSettingsIfBlocked
import app.retriever.core.SetupPolicy
import app.retriever.core.SetupStage
import app.retriever.core.Words
import app.retriever.core.motion.MascotMood
import app.retriever.model.UpgradeFlow
import app.retriever.motion.Mascot
import app.retriever.nav.Route
import app.retriever.ui.components.FileRow
import app.retriever.viewer.Opener
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    pillModifier: Modifier = Modifier,
    onNavigate: (Route) -> Unit = {},
    flow: SetupFlow = koinInject(),
    access: AccessService = koinInject(),
    upgrade: UpgradeFlow = koinInject(),
) {
    val context = LocalContext.current
    val ui by flow.ui.collectAsStateWithLifecycle()
    // Set when the person leaves for a permission, so coming back without any can say what to do.
    var askedFiles by rememberSaveable { mutableStateOf(false) }
    var allFilesOff by rememberSaveable { mutableStateOf(false) }
    var including by rememberSaveable { mutableStateOf(false) }
    var folderError by rememberSaveable { mutableStateOf<String?>(null) }
    var seeAll by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { flow.refresh() }
    // Leaves once a choice is made; access is never skipped.
    if (!ui.setup.showSetup) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onDone() }
    }
    BackHandler(enabled = SetupPolicy.back(ui.stage) != null, onBack = flow::back)
    val activity = LocalActivity.current
    val mediaLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            openSettingsIfBlocked(activity, it)
            access.changed()
            flow.refresh()
        }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) folderError = access.addFolder(uri)
        flow.refresh()
    }
    val allFilesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        access.refresh()
        if (access.state.value.allFiles) flow.continueFromAccess() else allFilesOff = true
    }
    val allowFiles = { openAllFilesSettings(context, allFilesLauncher::launch) }
    val actions = SetupActions(
        getStarted = flow::getStarted,
        allowFiles = allowFiles,
        chooseInstead = { including = true },
        allowMedia = {
            askedFiles = true
            mediaLauncher.launch(Access.mediaPermissions(Build.VERSION.SDK_INT).toTypedArray())
        },
        addFolder = {
            askedFiles = true
            folderError = null
            folderLauncher.launch(null)
        },
        continueFromAccess = {
            including = false
            flow.continueFromAccess()
        },
        seeAll = { seeAll = true },
        finish = flow::finish,
        useMobileData = upgrade::useMobileData,
        retryDownload = upgrade::download,
    )
    // The first search is hosted by the real Search, whose field and full-screen results sit outside SetupContent's scroll.
    if (ui.stage == SetupStage.SAMPLE) {
        DemoScreen(ui, actions, onNavigate, modifier, pillModifier)
    } else {
        SetupContent(ui, modifier, actions = actions, allFilesOff = allFilesOff)
    }
    if (including && ui.stage == SetupStage.ACCESS) {
        ModalBottomSheet(onDismissRequest = { including = false }) { IncludeSheet(ui, askedFiles, folderError, actions) }
    }
    // Every planned file, read or not, so the list answers what is done and what isn't; only read ones open, as on iOS.
    if (seeAll) {
        ModalBottomSheet(onDismissRequest = { seeAll = false }) {
            LazyColumn {
                items(ui.sample.orEmpty(), key = { it.index }) { f ->
                    FileRow(f.file, readNote = f.note ?: Words.READ, enabled = f.read) { Opener.open(context, f.file, "", onNavigate) }
                }
            }
        }
    }
}

/** Hello and the access screen: a persistent mascot above the stage's body, which slides on to the next. */
@Composable
fun SetupContent(
    ui: SetupUi,
    modifier: Modifier = Modifier,
    actions: SetupActions = SetupActions(),
    allFilesOff: Boolean = false,
    reduceMotion: Boolean = !ValueAnimator.areAnimatorsEnabled(),
) {
    val stage = ui.stage
    Scaffold(
        modifier.fillMaxSize(),
        bottomBar = {
            // The Scaffold paints its background edge to edge and insets only its content; a bottomBar must inset itself.
            Box(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.widthIn(max = 552.dp)) {
                    when (stage) {
                        SetupStage.WELCOME -> HelloActions(ui, actions)
                        SetupStage.ACCESS -> AccessActions(ui, allFilesOff, actions)
                        SetupStage.SAMPLE, SetupStage.DONE -> Unit
                    }
                }
            }
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            Column(
                // Capped inside the scroll, so the gutters scroll too. At least the viewport tall, with spare height split
                // above and below, for every stage alike, so nothing switches arrangement between stages.
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
                    .wrapContentWidth().widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            ) {
                Mascot(ui.mood(), Modifier.align(Alignment.CenterHorizontally).width(230.dp), settles = false)
                val spatial = spring(SPATIAL_DAMPING, SPATIAL_STIFFNESS, IntOffset.VisibilityThreshold)
                val effects = spring<Float>(1f, EFFECTS_STIFFNESS)
                // Shared axis X, the first run moving forward; with animations off, the next stage simply replaces this one.
                AnimatedContent(
                    stage,
                    transitionSpec = {
                        if (reduceMotion) {
                            EnterTransition.None togetherWith ExitTransition.None
                        } else {
                            (slideInHorizontally(spatial) { it / 8 } + fadeIn(effects)) togetherWith
                                (slideOutHorizontally(spatial) { -it / 8 } + fadeOut(effects))
                        }
                    },
                    label = "stage",
                ) { s ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (s) {
                            SetupStage.WELCOME -> Hello(ui, reduceMotion)

                            SetupStage.ACCESS -> AccessStage()

                            // SAMPLE is hosted by SetupScreen, outside this scroll; DONE leaves for Home.
                            SetupStage.SAMPLE, SetupStage.DONE -> Unit
                        }
                    }
                }
            }
        }
    }
}

// M3's standard motion scheme springs (default spatial and effects); MaterialTheme.motionScheme is internal in material3 1.4.
private const val SPATIAL_DAMPING = 0.9f
private const val SPATIAL_STIFFNESS = 700f
private const val EFFECTS_STIFFNESS = 1600f

/** The motion is the state: hello, a sniff at what to search, then it walks while the first files process and rests once paused or done. */
internal fun SetupUi.mood(): MascotMood = when {
    stage == SetupStage.WELCOME -> MascotMood.WELCOME
    stage == SetupStage.ACCESS -> MascotMood.SEARCHING
    stage == SetupStage.SAMPLE && reading -> MascotMood.WALKING
    else -> MascotMood.RESTING
}
