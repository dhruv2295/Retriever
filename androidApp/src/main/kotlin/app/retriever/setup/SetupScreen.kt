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
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import app.retriever.core.IndexState
import app.retriever.core.Retriever
import app.retriever.core.SetupStage
import app.retriever.core.SourceFile
import app.retriever.core.motion.MascotMood
import app.retriever.motion.Mascot
import app.retriever.nav.Route
import app.retriever.ui.components.FileRow
import app.retriever.viewer.Opener
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigate: (Route) -> Unit = {},
    flow: SetupFlow = koinInject(),
    access: AccessService = koinInject(),
    retriever: Retriever = koinInject(),
) {
    val requestReading = app.retriever.indexing.rememberReadingPermission()
    val context = LocalContext.current
    val ui by flow.ui.collectAsStateWithLifecycle()
    val index = flow.reading.collectAsStateWithLifecycle()
    // Set when the user leaves for a permission, so coming back without any can say what to do.
    var askedFiles by rememberSaveable { mutableStateOf(false) }
    var folderError by rememberSaveable { mutableStateOf<String?>(null) }
    var all by remember { mutableStateOf<List<SourceFile>?>(null) }
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { flow.refresh() }
    // Leaves once setup is done or skipped; access is never skipped.
    if (!ui.setup.showSetup) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onDone() }
    }
    BackHandler(enabled = ui.stage == SetupStage.TRY || ui.stage == SetupStage.CHOOSE, onBack = flow::back)
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
    val actions = SetupActions(
        getStarted = flow::getStarted,
        searchByName = flow::searchByName,
        allowFiles = {
            askedFiles = true
            openAllFilesSettings(context)
        },
        allowMedia = {
            askedFiles = true
            mediaLauncher.launch(Access.mediaPermissions(Build.VERSION.SDK_INT).toTypedArray())
        },
        addFolder = {
            askedFiles = true
            folderError = null
            folderLauncher.launch(null)
        },
        continueFromAccess = flow::continueFromAccess,
        tryNow = flow::tryNow,
        seeAll = { scope.launch { all = retriever.readFiles(retriever.scopeIds.value.orEmpty().toSet()) } },
        continueToCommit = flow::continueToCommit,
        commit = { charging -> requestReading { flow.commit(charging) } },
        decideLater = flow::decideLater,
        skip = flow::skip,
    )
    // The real Search is a Scaffold with lazy lists, so it cannot sit inside SetupContent's scrolling column.
    if (ui.stage == SetupStage.TRY) {
        TryItScreen(ui, actions, onNavigate)
    } else {
        SetupContent(ui, modifier, filesDenied = askedFiles, folderError = folderError, actions = actions, index = { index.value })
    }
    all?.let { files ->
        ModalBottomSheet(onDismissRequest = { all = null }) {
            LazyColumn {
                items(files, key = { it.uri }) { f -> FileRow(f) { Opener.open(context, f, "", onNavigate) } }
            }
        }
    }
}

/** One screen for the whole first run: a persistent mascot above the stage's body, which slides on to the next. */
@Composable
fun SetupContent(
    ui: SetupUi,
    modifier: Modifier = Modifier,
    filesDenied: Boolean = false,
    actions: SetupActions = SetupActions(),
    folderError: String? = null,
    index: () -> IndexState = { IndexState() },
    reduceMotion: Boolean = !ValueAnimator.areAnimatorsEnabled(),
) {
    val stage = ui.stage
    val taste = ui.taste
    val mascotWidth by animateDpAsState(
        if (stage == SetupStage.WELCOME) 260.dp else 160.dp,
        if (reduceMotion) snap() else spring(SPATIAL_DAMPING, SPATIAL_STIFFNESS),
    )
    Scaffold(modifier.fillMaxSize().safeDrawingPadding(), bottomBar = {
        if (stage == SetupStage.SAMPLE) Surface { SampleFooter(taste?.readyToTry == true, actions) }
    }) { padding ->
        Column(
            // Capped inside the scroll, so the gutters scroll too.
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .wrapContentWidth().widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (stage != SetupStage.CHOOSE) {
                Mascot(ui.mood(), Modifier.align(Alignment.CenterHorizontally).padding(top = 32.dp).width(mascotWidth), settles = false)
            }
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
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    when (s) {
                        SetupStage.WELCOME -> Welcome(ui, actions)

                        SetupStage.ACCESS -> AccessStage(ui, filesDenied, folderError, actions)

                        SetupStage.SAMPLE -> taste?.let { SampleStage(ui, it, index, actions) }

                        SetupStage.CHOOSE -> CommitStage(ui, actions)

                        // TRY is hosted by SetupScreen, outside this scroll; DONE leaves for Home.
                        SetupStage.TRY, SetupStage.DONE -> Unit
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

/** The motion is the state: it walks while the sample reads and rests once it is done. */
internal fun SetupUi.mood(): MascotMood = when {
    stage == SetupStage.WELCOME -> MascotMood.WELCOME
    stage == SetupStage.SAMPLE && taste?.readyKinds?.size != taste?.byKind?.size -> MascotMood.WALKING
    else -> MascotMood.RESTING
}
