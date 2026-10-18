/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/DownloadAndTryButton.kt and ui/common/Utils.kt (Apache-2.0). Modified for Retriever: the download branch moved to FirstRunStages.kt; notification permission uses the same check-then-RequestPermission pattern as Utils.kt.

package app.retriever.setup

import android.Manifest
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.retriever.core.motion.MascotMood
import app.retriever.model.ModelState
import app.retriever.model.TuneState
import app.retriever.motion.Mascot
import app.retriever.nav.Route
import org.koin.compose.koinInject

@Composable
fun SetupScreen(onDone: () -> Unit, onNavigate: (Route) -> Unit = {}, flow: SetupFlow = koinInject(), access: AccessService = koinInject()) {
    val context = LocalContext.current
    val ui by flow.ui.collectAsStateWithLifecycle()
    // Set when the user leaves for a permission, so coming back without any can say what to do.
    var askedFiles by rememberSaveable { mutableStateOf(false) }
    var folderError by rememberSaveable { mutableStateOf<String?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { flow.refresh() }
    val leave = ui.stage == SetupStage.DONE
    LaunchedEffect(leave) { if (leave) onDone() }
    val notificationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { flow.refresh() }
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
        getStarted = { flow.getStarted() },
        readEverything = { flow.getStarted(readEverything = true) },
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
        allowNotifications = {
            if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        dismissNotifications = flow::dismissNotificationAsk,
        useMobileData = flow::useMobileData,
        waitForWifi = flow::waitForWifi,
        retry = flow::retry,
        stopDownload = flow::cancelDownload,
        search = onDone,
        tryNow = flow::tryNow,
        continueToCommit = flow::continueToCommit,
        commit = flow::commit,
        decideLater = flow::decideLater,
        skip = flow::skip,
    )
    // The real Search is a Scaffold with lazy lists, so it cannot sit inside SetupContent's scrolling column.
    if (ui.stage == SetupStage.TRY) {
        TryItScreen(ui, actions, onNavigate)
    } else {
        SetupContent(ui, filesDenied = askedFiles && !ui.files, folderError = folderError, actions = actions)
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
) {
    val context = LocalContext.current
    val size = ui.build?.sizeBytes?.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) }
    val stage = ui.stage
    val mascotWidth by animateDpAsState(if (stage == SetupStage.WELCOME) 260.dp else 160.dp, spring(SPATIAL_DAMPING, SPATIAL_STIFFNESS))
    Surface(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Column(
                // Capped inside the scroll, so the gutters scroll too.
                Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
                    .wrapContentWidth().widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (stage != SetupStage.COMMIT) Mascot(ui.mood(), Modifier.align(Alignment.CenterHorizontally).padding(top = 32.dp).width(mascotWidth))
                val spatial = spring(SPATIAL_DAMPING, SPATIAL_STIFFNESS, IntOffset.VisibilityThreshold)
                val effects = spring<Float>(1f, EFFECTS_STIFFNESS)
                // Shared axis X: the first run only moves forward.
                AnimatedContent(
                    stage,
                    transitionSpec = {
                        (slideInHorizontally(spatial) { it / 8 } + fadeIn(effects)) togetherWith
                            (slideOutHorizontally(spatial) { -it / 8 } + fadeOut(effects))
                    },
                    label = "stage",
                ) { s ->
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        when (s) {
                            SetupStage.WELCOME -> Welcome(ui, size, actions)

                            SetupStage.ACCESS -> AccessStage(ui, filesDenied, folderError, actions)

                            SetupStage.WAITING -> Waiting(ui, size, folderError, actions)

                            SetupStage.READ_FEW -> ui.taste?.let { ReadingFew(it, actions) }

                            SetupStage.COMMIT -> CommitStage(ui, actions)

                            // TRY is hosted by SetupScreen, outside this scroll; DONE leaves for Home.
                            SetupStage.TRY, SetupStage.DONE -> Unit
                        }
                    }
                }
            }
            if (stage == SetupStage.WAITING || stage == SetupStage.READ_FEW) {
                TextButton(
                    onClick = actions.skip,
                    modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp),
                ) { Text("Skip") }
            }
        }
    }

    if (ui.askMobileData) {
        AlertDialog(
            onDismissRequest = actions.waitForWifi,
            title = { Text("Download over mobile data?") },
            text = { Text(if (size != null) "The download is $size. On Wi-Fi it won't use your data." else "On Wi-Fi it won't use your data.") },
            confirmButton = { TextButton(onClick = actions.useMobileData) { Text("Use mobile data") } },
            dismissButton = { TextButton(onClick = actions.waitForWifi) { Text("Wait for Wi-Fi") } },
        )
    }
}

// M3's standard motion scheme springs (default spatial and effects); MaterialTheme.motionScheme is internal in material3 1.4.
private const val SPATIAL_DAMPING = 0.9f
private const val SPATIAL_STIFFNESS = 700f
private const val EFFECTS_STIFFNESS = 1600f

/** The motion is the state: it walks while something runs, rests while it waits, and looks sheepish on a failure. */
internal fun SetupUi.mood(): MascotMood = when {
    stage == SetupStage.WELCOME -> MascotMood.WELCOME
    stage == SetupStage.READ_FEW -> if (taste?.readyKinds?.size == taste?.byKind?.size) MascotMood.RESTING else MascotMood.WALKING
    model is ModelState.Failed || tune is TuneState.Failed -> MascotMood.TROUBLE
    model == ModelState.WaitingForWifi || downloadStopped || textOnly -> MascotMood.RESTING
    else -> MascotMood.WALKING
}
