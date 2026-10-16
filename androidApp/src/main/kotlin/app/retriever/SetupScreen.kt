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
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.retriever.core.Backend
import app.retriever.core.ModelBuild
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val progress: Float, val indeterminate: Boolean) : ModelState
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
    val files: Boolean = false,
    val notifications: Boolean = false,
    val build: ModelBuild? = null,
    /** The chip-specific model failed here; the screen explains the second download. */
    val fellBack: Boolean = false,
    val model: ModelState = ModelState.Missing,
    val tune: TuneState = TuneState.Idle,
)

fun tuneSummary(backend: Backend, imageMs: Double?): String {
    val device = when (backend) {
        Backend.NPU -> "this phone's AI chip"
        Backend.GPU -> "the graphics chip"
        Backend.CPU -> "the processor"
    }
    val speed = imageMs?.let { " · about %.1f s per photo".format(it / 1000) } ?: ""
    return "Using $device$speed"
}

class SetupViewModel(private val graph: AppGraph) : ViewModel() {
    private val app = graph.app
    private val retriever = graph.retriever
    private val download = graph.download
    private val downloads = app.getSystemService(DownloadManager::class.java)
    private val _ui = MutableStateFlow(SetupUi())
    val ui: StateFlow<SetupUi> = _ui
    private var downloadId: Long? = null

    /** A download being tracked or a file being hashed. */
    private var modelJob: Job? = null

    /** Called on every resume: permissions, the model file and the required build can change while we are away. */
    fun refresh() {
        viewModelScope.launch {
            retriever.refreshSetUp()
            val b = retriever.requiredBuild.value
            val files = Environment.isExternalStorageManager()
            val notifications = ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            val (ready, inPlace, fellBack) = withContext(Dispatchers.IO) {
                Triple(
                    download.ready(b),
                    download.file(b).length() == b.sizeBytes,
                    retriever.fellBack(),
                )
            }
            _ui.update { s ->
                s.copy(files = files, notifications = notifications, build = b, fellBack = fellBack, tune = tuneAfterRefresh(s.tune, fellBack))
            }
            if (modelJob?.isActive == true) return@launch
            if (ready) {
                _ui.update { it.copy(model = ModelState.Done) }
                return@launch
            }
            // A download still running (this process died mid-download) is tracked, never hashed or deleted under it.
            val active = withContext(Dispatchers.IO) { activeDownload(b) }
            when {
                active != null -> track(b) { active }
                // A file from before hashes were pinned (or one downloaded before a crash): check it, never fetch it twice.
                inPlace -> modelJob = launch { verifyModel(b, afterDownload = false) }
                else -> _ui.update { it.copy(model = if (it.model is ModelState.Failed) it.model else ModelState.Missing) }
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

    fun downloadModel() {
        val b = retriever.requiredBuild.value
        viewModelScope.launch {
            // A failed NPU build is useless now: free its space before checking for the generic one.
            val hasSpace = withContext(Dispatchers.IO) {
                if (!b.isNpu) download.prune(b)
                download.hasSpaceFor(b)
            }
            if (!hasSpace) {
                val needGb = (b.sizeBytes + (300L shl 20)) / 1e9
                _ui.update { it.copy(model = ModelState.Failed("Needs %.1f GB free".format(needGb))) }
                return@launch
            }
            _ui.update { it.copy(model = ModelState.Downloading(0f, true)) }
            track(b) { download.start(b) }
        }
    }

    fun cancelDownload() {
        modelJob?.cancel()
        val b = retriever.requiredBuild.value
        viewModelScope.launch(Dispatchers.IO) {
            downloadId?.let { downloads.remove(it) }
            download.file(b).delete()
            _ui.update { it.copy(model = ModelState.Missing) }
        }
    }

    fun tune() {
        if (_ui.value.tune is TuneState.Running) return
        _ui.update { it.copy(tune = TuneState.Running) }
        viewModelScope.launch {
            val next = try {
                val r = calibrate(graph, app, tuneAgain = false)
                TuneState.Done(tuneSummary(r.chosen, r.backends.firstOrNull { it.backend == r.chosen }?.imageMs))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Errors too (a missing API, OOM): a crash here would leave setup unfinishable.
                Log.e("Retriever", "calibration failed", e)
                TuneState.Failed(e.message ?: e.toString())
            }
            _ui.update { it.copy(tune = next) }
        }
    }

    private var started = false

    fun start(onDone: () -> Unit) {
        if (started) return
        started = true
        IndexScheduler.indexNow(app)
        IndexScheduler.schedulePeriodic(app, retriever.prefs.value.onlyWhileCharging)
        onDone()
    }

    private fun activeDownload(b: ModelBuild): Long? {
        val query = DownloadManager.Query().setFilterByStatus(
            DownloadManager.STATUS_PENDING or DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PAUSED,
        )
        downloads.query(query).use { c ->
            val uri = c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)
            val id = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            while (c.moveToNext()) if (c.getString(uri) == b.url) return c.getLong(id)
        }
        return null
    }

    private fun track(b: ModelBuild, findId: () -> Long) {
        modelJob?.cancel()
        modelJob = viewModelScope.launch(Dispatchers.IO) {
            val id = try {
                findId()
            } catch (e: Exception) {
                Log.e("Retriever", "download could not start", e)
                _ui.update { it.copy(model = ModelState.Failed("Download didn't finish")) }
                return@launch
            }
            downloadId = id
            var last: ModelDownload.Progress? = null
            download.progress(id).collect { p ->
                last = p
                if (!p.done && !p.failed) {
                    val fraction = if (p.total > 0) (p.bytes.toFloat() / p.total).coerceIn(0f, 1f) else 0f
                    _ui.update { it.copy(model = ModelState.Downloading(fraction, p.total <= 0)) }
                }
            }
            if (last?.done == true) {
                verifyModel(b, afterDownload = true)
            } else {
                downloads.remove(id)
                download.file(b).delete()
                Log.w("Retriever", "model download failed")
                _ui.update { it.copy(model = ModelState.Failed("Download didn't finish")) }
            }
        }
    }

    /** Hashes [b]; on a match the model is ready, every other model file goes, and set-up is read again. */
    private suspend fun verifyModel(b: ModelBuild, afterDownload: Boolean) {
        _ui.update { it.copy(model = ModelState.Checking) }
        val ok = download.verify(b)
        if (ok) {
            withContext(Dispatchers.IO) { download.prune(keep = b) }
            retriever.refreshSetUp()
        }
        _ui.update {
            it.copy(
                model = when {
                    ok -> ModelState.Done
                    afterDownload -> ModelState.Failed("The download was damaged. Try again.")
                    else -> ModelState.Missing
                },
                fellBack = it.fellBack && !ok,
                tune = if (ok) tuneAfterRefresh(it.tune, it.fellBack) else it.tune,
            )
        }
    }
}

@Composable
fun SetupScreen(onDone: () -> Unit, vm: SetupViewModel = appViewModel { SetupViewModel(it) }) {
    val context = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    var notificationsSkipped by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    val notificationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }
    val modelDone = ui.model is ModelState.Done
    val tuned = ui.tune is TuneState.Done
    val size = ui.build?.let { Format.bytes(it.sizeBytes) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Set up Retriever", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Four quick steps, then Retriever reads your files on this phone. Nothing leaves it.",
                style = MaterialTheme.typography.bodyLarge,
            )

            Step("Let Retriever read your files", "So it can find what's inside them.", ui.files) {
                if (!ui.files) {
                    OutlinedButton(onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    }) { Text("Allow") }
                }
            }

            Step("Show indexing progress", "A small notification while it works. You can skip this.", ui.notifications) {
                if (!ui.notifications && !notificationsSkipped) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }) { Text("Allow") }
                        OutlinedButton(onClick = { notificationsSkipped = true }) { Text("Skip") }
                    }
                }
            }

            Step(
                if (size != null) "Download the search model ($size, once)" else "Download the search model",
                if (ui.fellBack && size != null) {
                    "This phone's AI chip couldn't run the model, so Retriever will use the graphics chip. One more download: $size."
                } else {
                    "Best on Wi-Fi."
                },
                modelDone,
            ) {
                when (val m = ui.model) {
                    ModelState.Done -> Unit
                    is ModelState.Downloading -> DownloadProgress(m, vm::cancelDownload)
                    ModelState.Checking -> Busy("Checking the model file…")
                    is ModelState.Failed -> {
                        Text(m.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = vm::downloadModel) { Text("Try again") }
                    }
                    ModelState.Missing -> OutlinedButton(onClick = vm::downloadModel, enabled = ui.files) { Text("Download") }
                }
            }

            Step(
                "Tune Retriever for this phone (about a minute)",
                (ui.tune as? TuneState.Done)?.summary ?: "Finds the fastest way to read your photos.",
                tuned,
            ) {
                when (ui.tune) {
                    is TuneState.Done -> Unit
                    TuneState.Running -> Busy("Tuning… this takes about a minute")
                    is TuneState.Failed -> {
                        Text("Couldn't tune Retriever for this phone", color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = vm::tune, enabled = ui.files && modelDone) { Text("Try again") }
                    }
                    TuneState.Idle -> OutlinedButton(onClick = vm::tune, enabled = ui.files && modelDone) { Text("Tune") }
                }
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { vm.start(onDone) },
                enabled = ui.files && modelDone && tuned,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start") }
        }
    }
}

@Composable
private fun Busy(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Step(title: String, detail: String, done: Boolean, action: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(
            if (done) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = if (done) "Done" else "Not done yet",
            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!done) action()
        }
    }
}

@Composable
private fun DownloadProgress(state: ModelState.Downloading, onStop: () -> Unit) {
    val percent = (state.progress * 100).toInt()
    val description = "Downloading the search model, $percent percent"
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            "$percent%",
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            modifier = Modifier.padding(end = 8.dp),
        )
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
        ) { Icon(Icons.Filled.Close, contentDescription = "Stop the download") }
    }
}
