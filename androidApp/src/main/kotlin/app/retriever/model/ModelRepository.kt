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

package app.retriever.model

import android.content.Context
import android.util.Log
import app.retriever.BuildConfig
import app.retriever.core.Backend
import app.retriever.core.ModelBuild
import app.retriever.core.Retriever
import app.retriever.diagnostics.LogSink
import app.retriever.indexing.IndexService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The search model on this phone, for setup and Settings alike: one state, one download at a time. Work runs in the
 * app's [scope], so leaving a screen never drops a download.
 */
class ModelRepository(
    context: Context,
    private val download: ModelDownload,
    private val retriever: Retriever,
    private val scope: CoroutineScope,
    private val index: IndexService = IndexService(context, retriever),
    private val progress: (Long) -> Flow<ModelDownload.Progress> = download::progress,
) {
    private val _state = MutableStateFlow<ModelState>(ModelState.Missing)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    /** The download being tracked or the file being hashed. */
    private var job: Job? = null

    @Volatile private var id: Long? = null

    /** Reads the disk and DownloadManager again: picks up a download a killed process left, hashes a file already in place. */
    suspend fun sync(b: ModelBuild) {
        if (job?.isActive == true) return
        val (ready, active, inPlace) = withContext(Dispatchers.IO) {
            Triple(download.ready(b), download.active(b), download.file(b).length() == b.sizeBytes)
        }
        // The state is set before returning, so a caller deciding its next step never queues a second download.
        when {
            ready -> _state.value = ModelState.Done

            // A running download is tracked, never hashed or deleted under it.
            active != null -> {
                _state.value = ModelState.Downloading(0f, true)
                launchOnce { track(b, active) }
            }

            inPlace -> {
                _state.value = ModelState.Checking
                launchOnce { verify(b, afterDownload = false) }
            }

            else -> _state.update { if (it is ModelState.Failed) it else ModelState.Missing }
        }
    }

    /** Downloads [b], or follows the download of it already queued. */
    fun start(b: ModelBuild) = launchOnce {
        val running = withContext(Dispatchers.IO) { download.active(b) }
        if (running == null && !download.hasSpaceFor(b)) {
            _state.value = ModelState.Failed(spaceMessage(b))
            return@launchOnce
        }
        _state.value = ModelState.Downloading(0f, true)
        val id = try {
            running ?: download.start(b, retriever.prefs.value)
        } catch (e: Exception) {
            Log.e("Retriever", "download could not start", e)
            _state.value = ModelState.Failed(STOPPED)
            return@launchOnce
        }
        track(b, id)
    }

    /** Stops the download and deletes what it fetched. */
    suspend fun cancel(b: ModelBuild) {
        job?.cancelAndJoin()
        withContext(Dispatchers.IO) {
            // A Cancel right after enqueue can come before the id is set.
            (id ?: download.active(b))?.let(download::remove)
            download.file(b).delete()
        }
        id = null
        _state.value = ModelState.Missing
    }

    /** A failure stays on screen until the user tries again. */
    fun clearFailure() = _state.update { if (it is ModelState.Failed) ModelState.Missing else it }

    private fun launchOnce(block: suspend () -> Unit) {
        synchronized(this) {
            if (job?.isActive == true) return
            job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A disk or DownloadManager failure ends this attempt, not the app; the user can try again.
                    LogSink.write(BuildConfig.DEBUG, "model download or check failed", e)
                    _state.value = ModelState.Failed(STOPPED)
                }
            }.also { it.start() }
        }
    }

    private suspend fun track(b: ModelBuild, id: Long) {
        this.id = id
        var last: ModelDownload.Progress? = null
        progress(id).collect { p ->
            last = p
            if (!p.done && !p.failed) {
                val fraction = if (p.total > 0) (p.bytes.toFloat() / p.total).coerceIn(0f, 1f) else 0f
                _state.value = if (p.waitingForWifi) ModelState.WaitingForWifi else ModelState.Downloading(fraction, p.total <= 0)
            }
        }
        this.id = null
        if (last?.done == true) {
            verify(b, afterDownload = true)
        } else {
            Log.w("Retriever", "model download failed")
            withContext(Dispatchers.IO) {
                download.remove(id)
                download.file(b).delete()
            }
            _state.value = ModelState.Failed(STOPPED)
        }
    }

    /** On a match every other model file goes and set-up is read again; files read by name alone get their meaning. */
    private suspend fun verify(b: ModelBuild, afterDownload: Boolean) {
        _state.value = ModelState.Checking
        if (download.verify(b)) {
            withContext(Dispatchers.IO) { download.prune(keep = b) }
            retriever.refreshSetUp()
            _state.value = ModelState.Done
            index.indexNow()
        } else {
            _state.value = if (afterDownload) ModelState.Failed(DAMAGED) else ModelState.Missing
        }
    }

    companion object {
        const val STOPPED = "The download stopped. Check your connection and try again."
        const val DAMAGED = "The download was damaged. Try again."
    }
}

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

fun tuneSummary(backend: Backend, imageMs: Double?): String {
    val device = when (backend) {
        Backend.NPU -> "this phone's AI chip"
        Backend.GPU -> "the graphics chip"
        Backend.CPU -> "the processor"
    }
    val speed = imageMs?.let { " · about %.1f s per photo".format(it / 1000) } ?: ""
    return "Using $device$speed"
}

fun spaceMessage(b: ModelBuild): String = "Needs %.1f GB free. Free up some space, then try again.".format(ModelDownload.neededBytes(b) / 1e9)
