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
// Adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/DownloadAndTryButton.kt (Apache-2.0). Modified for Rummage: only the in-progress download branch, driven by a polled Float; notification permission uses the same check-then-RequestPermission pattern as ui/common/Utils.kt.
package app.rummage

import android.Manifest
import android.app.Application
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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import app.rummage.core.Backend
import app.rummage.core.ModelVariant
import app.rummage.core.prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

private const val MODEL_URL =
    "https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/resolve/main/embeddinggemma-2-740m.litertlm"

sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val progress: Float, val indeterminate: Boolean, val note: String? = null) : ModelState
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
    val model: ModelState = ModelState.Missing,
    val tune: TuneState = TuneState.Idle,
)

fun tuneSummary(backend: Backend, imageMs: Double?): String {
    val device = when (backend) {
        Backend.GPU -> "the GPU"
        Backend.NPU -> "the NPU"
        Backend.CPU -> "the CPU"
    }
    val speed = imageMs?.let { " · about %.1f s per photo".format(it / 1000) } ?: ""
    return "Using $device$speed"
}

class SetupViewModel(private val app: Application) : AndroidViewModel(app) {
    private val graph = (app as RummageApp).graph
    private val downloads = app.getSystemService(DownloadManager::class.java)
    private val _ui = MutableStateFlow(SetupUi())
    val ui: StateFlow<SetupUi> = _ui
    private var downloadId: Long? = null
    private var downloadJob: Job? = null

    /** Called on every resume: permissions and the model file can change while we are away. */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val modelDone = graph.modelFile().length() == AppGraph.MODEL_BYTES
            val profile = graph.store.profile()
            _ui.update { s ->
                s.copy(
                    files = Environment.isExternalStorageManager(),
                    notifications = ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED,
                    model = when {
                        modelDone -> ModelState.Done
                        s.model is ModelState.Done -> ModelState.Missing
                        else -> s.model
                    },
                    tune = if (s.tune is TuneState.Idle && profile != null) {
                        TuneState.Done(tuneSummary(profile.backend, null))
                    } else {
                        s.tune
                    },
                )
            }
            if (modelDone) downloadJob?.cancel()
            else if (downloadJob?.isActive != true) activeDownload()?.let { track { it } }
        }
    }

    fun downloadModel() {
        _ui.update { it.copy(model = ModelState.Downloading(0f, true)) }
        track {
            activeDownload() ?: run {
                val file = graph.modelFile()
                if (file.exists() && file.length() != AppGraph.MODEL_BYTES) file.delete()
                val request = DownloadManager.Request(Uri.parse(MODEL_URL))
                    .setTitle("Rummage search model")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationInExternalFilesDir(app, "models", ModelVariant.M740.fileName)
                downloads.enqueue(request)
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            downloadId?.let { downloads.remove(it) }
            graph.modelFile().delete()
            _ui.update { it.copy(model = ModelState.Missing) }
        }
    }

    fun tune() {
        if (_ui.value.tune is TuneState.Running) return
        _ui.update { it.copy(tune = TuneState.Running) }
        viewModelScope.launch(Dispatchers.Default) {
            val next = try {
                val r = calibrate(graph, app, tuneAgain = false)
                TuneState.Done(tuneSummary(r.chosen, r.backends.firstOrNull { it.backend == r.chosen }?.imageMs))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Rummage", "calibration failed", e)
                TuneState.Failed(e.message ?: e.toString())
            }
            _ui.update { it.copy(tune = next) }
        }
    }

    private var started = false

    fun start(onDone: () -> Unit) {
        if (started) return
        started = true
        viewModelScope.launch(Dispatchers.IO) {
            IndexScheduler.indexNow(app)
            IndexScheduler.schedulePeriodic(app, graph.store.prefs().onlyWhileCharging)
            launch(Dispatchers.Main) { onDone() }
        }
    }

    private fun activeDownload(): Long? {
        val query = DownloadManager.Query().setFilterByStatus(
            DownloadManager.STATUS_PENDING or DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PAUSED,
        )
        downloads.query(query).use { c ->
            val uri = c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)
            val id = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            while (c.moveToNext()) if (c.getString(uri) == MODEL_URL) return c.getLong(id)
        }
        return null
    }

    private fun track(findId: () -> Long) {
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            val id = try {
                findId()
            } catch (e: Exception) {
                Log.e("Rummage", "download could not start", e)
                _ui.update { it.copy(model = ModelState.Failed("Download didn't finish")) }
                return@launch
            }
            downloadId = id
            poll(id)
        }
    }

    // Polls once a second and stops as soon as the download completes or fails.
    private suspend fun poll(id: Long) {
        while (true) {
            var status = -1
            var done = 0L
            var total = 0L
            var reason = 0
            val found = downloads.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (!c.moveToFirst()) return@use false
                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                true
            }
            val file: File = graph.modelFile()
            val next: ModelState? = when {
                !found -> ModelState.Missing
                status == DownloadManager.STATUS_SUCCESSFUL ->
                    if (file.length() == AppGraph.MODEL_BYTES) ModelState.Done else failed(id, file, reason)
                status == DownloadManager.STATUS_FAILED -> failed(id, file, reason)
                else -> {
                    _ui.update {
                        it.copy(
                            model = ModelState.Downloading(
                                if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f,
                                total <= 0,
                                if (status == DownloadManager.STATUS_PAUSED) pausedNote(reason) else null,
                            ),
                        )
                    }
                    null
                }
            }
            if (next != null) {
                _ui.update { it.copy(model = next) }
                return
            }
            delay(1000)
        }
    }

    private fun failed(id: Long, file: File, reason: Int): ModelState {
        downloads.remove(id)
        file.delete()
        Log.w("Rummage", "model download failed, reason $reason")
        return ModelState.Failed(
            if (reason == DownloadManager.ERROR_INSUFFICIENT_SPACE) "Not enough free space (needs 485 MB)"
            else "Download didn't finish",
        )
    }

    private fun pausedNote(reason: Int): String = when (reason) {
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for a connection"
        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi"
        DownloadManager.PAUSED_WAITING_TO_RETRY -> "Waiting to try again"
        else -> "Paused for now"
    }
}

@Composable
fun SetupScreen(onDone: () -> Unit, vm: SetupViewModel = viewModel()) {
    val context = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    var notificationsSkipped by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    val notificationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }
    val modelDone = ui.model is ModelState.Done
    val tuned = ui.tune is TuneState.Done

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Set up Rummage", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Four quick steps, then Rummage reads your files on this phone. Nothing leaves it.",
                style = MaterialTheme.typography.bodyLarge,
            )

            Step("Let Rummage read your files", "So it can find what's inside them.", ui.files) {
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

            Step("Download the search model (485 MB, once)", "Best on Wi-Fi.", modelDone) {
                when (val m = ui.model) {
                    ModelState.Done -> Unit
                    is ModelState.Downloading -> {
                        DownloadProgress(m, vm::cancelDownload)
                        m.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    is ModelState.Failed -> {
                        Text(m.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = vm::downloadModel) { Text("Try again") }
                    }
                    ModelState.Missing -> OutlinedButton(onClick = vm::downloadModel, enabled = ui.files) { Text("Download") }
                }
            }

            Step(
                "Tune Rummage for this phone (about a minute)",
                (ui.tune as? TuneState.Done)?.summary ?: "Finds the fastest way to read your photos.",
                tuned,
            ) {
                when (val t = ui.tune) {
                    is TuneState.Done -> Unit
                    TuneState.Running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        Text("Tuning… this takes about a minute", style = MaterialTheme.typography.bodyMedium)
                    }
                    is TuneState.Failed -> {
                        Text("Couldn't tune Rummage for this phone", color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = vm::tune) { Text("Try again") }
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
