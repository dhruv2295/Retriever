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
// Adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/DownloadAndTryButton.kt (Apache-2.0). Modified for Retriever: only the in-progress download branch, driven by ModelDownload's polled progress, with a free-space check and a hash check before the model counts as ready; notification permission uses the same check-then-RequestPermission pattern as ui/common/Utils.kt.
package app.retriever

import android.Manifest
import android.os.Build
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retriever.core.Backend
import app.retriever.core.CalibrationResult
import app.retriever.core.ModelBuild
import app.retriever.core.Retriever
import app.retriever.data.AccessService
import app.retriever.data.IndexService
import app.retriever.data.ModelRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val progress: Float, val indeterminate: Boolean) : ModelState
    data object WaitingForWifi : ModelState
    data object Checking : ModelState
    data class Failed(val reason: String) : ModelState
    data object Done : ModelState
}

sealed interface TuneState {
    data object Idle : TuneState
    data object Running : TuneState
    data class Done(val summary: String) : TuneState
    data class Failed(val message: String) : TuneState
}

data class SetupUi(
    val started: Boolean = false,
    val access: Access = Access.NONE,
    /** Too little memory for the model: no download, no tuning, names and words only. */
    val textOnly: Boolean = false,
    val ramGb: Double = 0.0,
    val notificationAsk: Boolean = false,
    val build: ModelBuild? = null,
    /** The chip-specific model failed here; the screen explains the second download. */
    val fellBack: Boolean = false,
    val model: ModelState = ModelState.Missing,
    val tune: TuneState = TuneState.Idle,
    val askMobileData: Boolean = false,
    val ready: Boolean = false,
    /** Null until the user picks mobile data or Wi-Fi. */
    val allowMetered: Boolean? = null,
    /** The file list was synced in this process, so names are searchable. */
    val namesSynced: Boolean = false,
    val scheduled: Boolean = false,
    /** The user stopped the download; it waits for them to start it again. */
    val downloadStopped: Boolean = false,
) {
    val files get() = access.ready
    val canSearch get() = started && files
}

enum class SetupStep { NONE, ASK_FILES, SYNC_NAMES, ASK_MOBILE_DATA, DOWNLOAD, VERIFY, TUNE, SCHEDULE, NEEDS_SPACE }

/** The next thing setup should do. Every step it names is safe to run twice. */
fun nextStep(ui: SetupUi, hasSpace: Boolean, metered: Boolean, profileExists: Boolean): SetupStep = when {
    !ui.started -> SetupStep.NONE
    !ui.files -> SetupStep.ASK_FILES
    ui.ready -> if (ui.scheduled) SetupStep.NONE else SetupStep.SCHEDULE
    !ui.namesSynced -> SetupStep.SYNC_NAMES
    ui.textOnly -> SetupStep.NONE
    ui.model == ModelState.Missing && !ui.downloadStopped -> when {
        !hasSpace -> SetupStep.NEEDS_SPACE
        metered && ui.allowMetered == null -> SetupStep.ASK_MOBILE_DATA
        else -> SetupStep.DOWNLOAD
    }
    ui.model == ModelState.Checking -> SetupStep.VERIFY
    ui.model == ModelState.Done && !profileExists && ui.tune == TuneState.Idle -> SetupStep.TUNE
    else -> SetupStep.NONE
}

fun tuneSummary(backend: Backend, imageMs: Double?): String {
    val device = when (backend) {
        Backend.NPU -> "this phone's AI chip"
        Backend.GPU -> "the graphics chip"
        Backend.CPU -> "the processor"
    }
    val speed = imageMs?.let { " · about %.1f s per photo".format(it / 1000) } ?: ""
    return "Using $device$speed"
}

fun spaceMessage(b: ModelBuild): String =
    "Needs %.1f GB free. Free up some space, then try again.".format(ModelDownload.neededBytes(b) / 1e9)

/**
 * Setup as one chain in the app's scope: leaving the screen or the app never stops it, and [refresh]
 * picks it up again when the user comes back.
 */
class SetupFlow(
    private val retriever: Retriever,
    private val models: ModelRepository,
    private val access: AccessService,
    private val index: IndexService,
    private val download: ModelDownload,
    private val scope: CoroutineScope,
    private val onMeteredNetwork: () -> Boolean,
    private val notificationsGranted: () -> Boolean,
    private val calibrate: suspend () -> CalibrationResult,
) {
    // Seeded synchronously: AppNav reads canSearch once to pick the first screen. Built only after setUp settles, so
    // the prefs are read; a finished setup counts as started for installs from before Prefs.setupStarted.
    private val _ui = MutableStateFlow(
        SetupUi(
            started = retriever.prefs.value.setupStarted || retriever.setUp.value == true,
            access = access.state.value,
            textOnly = retriever.textOnly,
            ramGb = retriever.probe.ramGb,
            allowMetered = if (retriever.prefs.value.allowMobileData) true else null,
        ),
    )
    val ui: StateFlow<SetupUi> = _ui
    private val mutex = Mutex()

    /** Set up with the model gone, as last seen; guarded by [mutex]. */
    private var wasMissing = false

    init {
        scope.launch { retriever.prefs.collect { p -> _ui.update { it.copy(started = it.started || p.setupStarted) } } }
        scope.launch {
            models.state.collect { model ->
                val done = model == ModelState.Done
                _ui.update {
                    it.copy(
                        model = model,
                        fellBack = it.fellBack && !done,
                        tune = if (done) tuneAfterRefresh(it.tune, it.fellBack) else it.tune,
                    )
                }
                if (done || model is ModelState.Failed || model == ModelState.Missing) advance()
            }
        }
        scope.launch {
            combine(retriever.setUp, retriever.modelMissing, ::Pair).collect { (setUp, missing) ->
                mutex.withLock {
                    // Only a ready flip acts here: before the first reload the model state is still a guess.
                    if (applyReady(setUp == true, missing)) step()
                }
            }
        }
    }

    /**
     * Ready is set up with the model in place. When the model goes after setup (an AI-chip fallback needs a new
     * build, or Free up space) the download is offered, never fetched unasked: the two look the same from here.
     * Returns whether setup is ready.
     */
    private fun applyReady(setUp: Boolean, missing: Boolean): Boolean {
        val gone = setUp && missing
        val ready = setUp && !missing
        _ui.update {
            it.copy(ready = ready, scheduled = it.scheduled && ready, downloadStopped = it.downloadStopped || (gone && !wasMissing))
        }
        wasMissing = gone
        return ready
    }

    /** Permissions, the model file and the required build can all change while the user is away. */
    fun refresh() {
        scope.launch {
            mutex.withLock {
                reload()
                step()
            }
        }
    }

    fun getStarted() {
        markStarted()
        refresh()
    }

    fun useMobileData() {
        _ui.update { it.copy(allowMetered = true, askMobileData = false) }
        scope.launch {
            retriever.updatePrefs { it.copy(allowMobileData = true) }
            mutex.withLock {
                // DownloadManager can't lift the Wi-Fi rule from a queued request, so it is queued again.
                if (models.state.value == ModelState.WaitingForWifi) models.cancel(retriever.requiredBuild.value)
                step()
            }
        }
    }

    fun waitForWifi() {
        _ui.update { it.copy(allowMetered = false, askMobileData = false) }
        scope.launch { retriever.updatePrefs { it.copy(allowMobileData = false) } }
        advance()
    }

    fun retry() {
        val modelFailed = models.state.value is ModelState.Failed
        models.clearFailure()
        _ui.update {
            when {
                modelFailed || it.downloadStopped -> it.copy(downloadStopped = false)
                it.tune is TuneState.Failed -> it.copy(tune = TuneState.Idle)
                else -> it
            }
        }
        refresh()
    }

    fun cancelDownload() {
        scope.launch {
            mutex.withLock {
                models.cancel(retriever.requiredBuild.value)
                _ui.update { it.copy(downloadStopped = true) }
            }
        }
    }

    fun dismissNotificationAsk() {
        _ui.update { it.copy(notificationAsk = false) }
        scope.launch { retriever.updatePrefs { it.copy(notificationsDismissed = true) } }
    }

    private fun markStarted() {
        _ui.update { it.copy(started = true) }
        scope.launch { retriever.updatePrefs { it.copy(setupStarted = true) } }
    }

    private fun advance() {
        scope.launch { mutex.withLock { step() } }
    }

    private suspend fun reload() {
        retriever.refreshSetUp()
        val b = retriever.requiredBuild.value
        val fellBack = withContext(Dispatchers.IO) { retriever.fellBack() }
        access.refresh()
        applyReady(retriever.setUp.value == true, retriever.modelMissing.value)
        _ui.update { s ->
            s.copy(
                access = access.state.value,
                notificationAsk = !notificationsGranted() && !retriever.prefs.value.notificationsDismissed,
                build = b,
                fellBack = fellBack,
                tune = tuneAfterRefresh(s.tune, fellBack),
            )
        }
        // A file from before hashes were pinned (or one downloaded before a crash) is checked, never fetched twice.
        models.sync(b)
    }

    private suspend fun decide(): SetupStep {
        // The repository's state, not the mirrored copy, so a download just queued is never queued again.
        val ui = _ui.value.copy(model = models.state.value)
        val b = retriever.requiredBuild.value
        val missing = ui.started && ui.files && !ui.ready && !ui.textOnly && ui.model == ModelState.Missing && !ui.downloadStopped
        val hasSpace = !missing || withContext(Dispatchers.IO) {
            // A failed NPU build is useless now: free its space before checking for the generic one.
            if (!b.isNpu) download.prune(b)
            download.hasSpaceFor(b)
        }
        val metered = missing && onMeteredNetwork()
        // With the model in place and access granted, set-up is exactly "a profile exists".
        return nextStep(ui, hasSpace, metered, profileExists = retriever.setUp.value == true)
    }

    private suspend fun step() {
        var s = decide()
        if (s == SetupStep.SYNC_NAMES) {
            // A batch without a model still syncs the file list, so names are searchable while the model downloads.
            index.indexNow()
            _ui.update { it.copy(namesSynced = true) }
            s = decide()
        }
        val b = retriever.requiredBuild.value
        when (s) {
            // VERIFY: the hash check reload started is still running.
            SetupStep.NONE, SetupStep.ASK_FILES, SetupStep.SYNC_NAMES, SetupStep.VERIFY -> Unit
            SetupStep.ASK_MOBILE_DATA -> _ui.update { it.copy(askMobileData = true) }
            // The repository checks the space again and says why it can't start.
            SetupStep.NEEDS_SPACE, SetupStep.DOWNLOAD -> models.start(b)
            SetupStep.TUNE -> tune()
            SetupStep.SCHEDULE -> {
                // Runs once per process, so opening the app reads files even on battery: by design, a visible app
                // indexes now (Retriever.indexBatch). "Only read while charging" governs background reading.
                index.indexNow()
                index.schedulePeriodic(retriever.prefs.value.onlyWhileCharging)
                _ui.update { it.copy(scheduled = true) }
            }
        }
    }

    private fun tuneAfterRefresh(t: TuneState, fellBack: Boolean): TuneState =
        if (t is TuneState.Idle && retriever.setUp.value == true) {
            // After a fallback the last backend seen may be the chip that just failed.
            val backend = retriever.state.value.backend.takeUnless { fellBack }
            TuneState.Done(backend?.let { tuneSummary(it, null) } ?: "Tuned for this phone")
        } else {
            t
        }

    /** Runs once per chain; a failure waits for Try again rather than looping. */
    private fun tune() {
        _ui.update { it.copy(tune = TuneState.Running) }
        val before = retriever.requiredBuild.value
        scope.launch {
            val next = try {
                val r = calibrate()
                TuneState.Done(tuneSummary(r.chosen, r.backends.firstOrNull { it.backend == r.chosen }?.imageMs))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Errors too (a missing API, OOM): a crash here would leave setup unfinishable.
                Log.e("Retriever", "calibration failed", e)
                // The AI chip failed: a new build is required, and tuning runs again after its download.
                if (retriever.requiredBuild.value != before) TuneState.Idle else TuneState.Failed(e.message ?: e.toString())
            }
            _ui.update { it.copy(tune = next) }
            refresh()
        }
    }
}

class SetupActions(
    val getStarted: () -> Unit = {},
    val allowFiles: () -> Unit = {},
    val allowMedia: () -> Unit = {},
    val addFolder: () -> Unit = {},
    val allowNotifications: () -> Unit = {},
    val dismissNotifications: () -> Unit = {},
    val useMobileData: () -> Unit = {},
    val waitForWifi: () -> Unit = {},
    val retry: () -> Unit = {},
    val stopDownload: () -> Unit = {},
    val search: () -> Unit = {},
)

@Composable
fun SetupScreen(onDone: () -> Unit, flow: SetupFlow = koinInject(), access: AccessService = koinInject()) {
    val context = LocalContext.current
    val ui by flow.ui.collectAsStateWithLifecycle()
    // Set when the user leaves for a permission, so coming back without any can say what to do.
    var askedFiles by rememberSaveable { mutableStateOf(false) }
    var folderError by rememberSaveable { mutableStateOf<String?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { flow.refresh() }
    LaunchedEffect(ui.ready) { if (ui.ready) onDone() }
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
    SetupContent(
        ui,
        filesDenied = askedFiles && !ui.files,
        folderError = folderError,
        actions = SetupActions(
            getStarted = flow::getStarted,
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
        ),
    )
}

@Composable
fun SetupContent(
    ui: SetupUi,
    modifier: Modifier = Modifier,
    filesDenied: Boolean = false,
    actions: SetupActions = SetupActions(),
    folderError: String? = null,
) {
    val size = ui.build?.let { Format.bytes(it.sizeBytes) }
    Surface(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                // Capped inside the scroll, so the gutters scroll too.
                Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
                    .wrapContentWidth().widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Image(
                    painterResource(R.drawable.illus_welcome),
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = 240.dp).fillMaxWidth(),
                )
                Text("Find anything by what's inside it", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Retriever reads your photos, screenshots, videos, recordings, PDFs and documents on this phone, " +
                        "so you can search them in your own words. Nothing leaves your phone.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Muted("Files inside other apps' private folders can't be searched.")

                if (!ui.started) {
                    Button(onClick = actions.getStarted, modifier = Modifier.fillMaxWidth()) { Text("Get started") }
                    Muted(
                        when {
                            ui.textOnly -> textOnlyNote(ui.ramGb)
                            size != null -> "Needs a one-time download of $size. Best on Wi-Fi."
                            else -> "Needs a one-time download. Best on Wi-Fi."
                        },
                    )
                } else {
                    Steps(ui, size, filesDenied, folderError, actions)
                }
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

/** Emits into the caller's Column, so its spacing applies. */
@Composable
private fun ColumnScope.Steps(ui: SetupUi, size: String?, filesDenied: Boolean, folderError: String?, actions: SetupActions) {
    Step("Allow access to your files", ui.files) {
        if (!ui.files) {
            Button(onClick = actions.allowFiles, modifier = Modifier.fillMaxWidth()) { Text("Allow access to all files") }
            Muted("Recommended: Retriever can then search your documents, downloads and everything else.")
            OutlinedButton(onClick = actions.allowMedia, modifier = Modifier.fillMaxWidth()) { Text("Only photos, videos and audio") }
            OutlinedButton(onClick = actions.addFolder, modifier = Modifier.fillMaxWidth()) { Text("Add a folder of documents") }
            if (filesDenied) Muted("Nothing is allowed yet. Any one of these is enough to start.")
        } else if (!ui.access.allFiles) {
            val lines = ui.access.coverage()
            val covered = lines.filter { !it.gap }.map { it.text } + ui.access.folders.map { it.name }
            Muted("Retriever can search: " + covered.joinToString(" · "))
            if (lines.any { it.fix == Fix.ALLOW_MEDIA }) {
                OutlinedButton(onClick = actions.allowMedia) { Text("Allow photos, videos and music") }
            }
            OutlinedButton(onClick = actions.addFolder) { Text("Add a folder of documents") }
        }
        folderError?.let { ErrorText(it) }
    }

    if (ui.textOnly) {
        Muted(textOnlyNote(ui.ramGb))
    } else {
        ModelSteps(ui, size, actions)
    }

    if (ui.notificationAsk) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Show reading progress in a notification?", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = actions.allowNotifications) { Text("Allow") }
                TextButton(onClick = actions.dismissNotifications) { Text("Not now") }
            }
        }
    }

    when {
        ui.ready -> Button(onClick = actions.search, modifier = Modifier.fillMaxWidth()) { Text("Start searching") }
        ui.files -> Button(onClick = actions.search, modifier = Modifier.fillMaxWidth()) { Text("Search while this finishes") }
    }
}

/** The download and tuning steps, which a text-only phone skips. */
@Composable
private fun ColumnScope.ModelSteps(ui: SetupUi, size: String?, actions: SetupActions) {
    Step(if (size != null) "Download what Retriever needs · $size" else "Download what Retriever needs", ui.model == ModelState.Done) {
        if (ui.fellBack && ui.model != ModelState.Done && size != null) {
            Muted(
                "This phone's AI chip couldn't run it, so Retriever will use the graphics chip instead. " +
                    "That needs one more download ($size).",
            )
        }
        when (val m = ui.model) {
            ModelState.Done -> Unit
            is ModelState.Downloading -> DownloadProgress(m, actions.stopDownload)
            ModelState.WaitingForWifi -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Waiting for Wi-Fi", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = actions.useMobileData) { Text("Use mobile data") }
            }
            ModelState.Checking -> Busy("Checking the download…")
            is ModelState.Failed -> {
                ErrorText(m.reason)
                OutlinedButton(onClick = actions.retry) { Text("Try again") }
            }
            ModelState.Missing -> when {
                ui.downloadStopped -> {
                    Muted("Download stopped.")
                    OutlinedButton(onClick = actions.retry) { Text("Download") }
                }
                !ui.files -> Muted("Starts once Retriever can see your files.")
                else -> Muted("Starting…")
            }
        }
    }

    Step("Get to know this phone · a few minutes at most", ui.tune is TuneState.Done) {
        when (val t = ui.tune) {
            TuneState.Idle -> if (ui.model != ModelState.Done) Muted("Starts after the download.")
            TuneState.Running -> Busy("Testing how fast this phone reads photos…")
            is TuneState.Done -> Muted(t.summary)
            is TuneState.Failed -> {
                ErrorText("Couldn't finish this step.")
                OutlinedButton(onClick = actions.retry) { Text("Try again") }
            }
        }
    }
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ErrorText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun Busy(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Step(title: String, done: Boolean, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // One TalkBack stop for the title and whether it's done; the buttons below stay their own stops.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.semantics(mergeDescendants = true) {},
        ) {
            Icon(
                painterResource(if (done) R.drawable.ic_check_circle else R.drawable.ic_radio_button_unchecked),
                contentDescription = if (done) "Done" else "Not done yet",
                tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        Column(Modifier.padding(start = 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun DownloadProgress(state: ModelState.Downloading, onStop: () -> Unit) {
    val percent = (state.progress * 100).toInt()
    val description = if (state.indeterminate) "Starting the download" else "Downloading, $percent percent"
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (!state.indeterminate) {
            Text(
                "$percent%",
                style = MaterialTheme.typography.bodyMedium.tabular(),
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        val bar = Modifier.weight(1f).semantics {
            contentDescription = description
            if (!state.indeterminate) progressBarRangeInfo = ProgressBarRangeInfo(state.progress, 0f..1f)
        }
        if (state.indeterminate) {
            LinearProgressIndicator(modifier = bar, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        } else {
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = bar,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
        IconButton(
            onClick = onStop,
            colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.padding(start = 4.dp),
        ) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Stop the download") }
    }
}
