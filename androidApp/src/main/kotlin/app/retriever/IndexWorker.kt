/*
 * Copyright 2026 Google LLC
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
// Adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/smartalbum/SmartAlbumIndexingWorker.kt (Apache-2.0). Modified for Retriever: the per-photo loop, input data, setProgress, coordinator pause and sample branch are replaced by one Retriever.indexBatch, a foreground service only while the app is visible, and follow-up scheduling by NextRun.

package app.retriever

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.retriever.core.IndexState
import app.retriever.core.NextRun
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val graph = (applicationContext as RetrieverApp).graph
    private val retriever = graph.retriever
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val notificationId: Int = params.id.hashCode()
    private val pauseIntent by lazy {
        PendingIntent.getBroadcast(
            applicationContext,
            0,
            Intent(applicationContext, PauseReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // A stop (constraints, time limit, Pause) cancels doWork, and with it the batch inside indexBatch:
    // the cancelBatch() the platform's onStopped would call (it is final in CoroutineWorker).
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // No Process.setThreadPriority: it would demote a pooled IO thread for good, and the heavy work runs on Dispatchers.Default and MediaPipe's own threads.
        // A fresh process (WorkManager woke it) has default flows until this read.
        retriever.refreshSetUp()
        // Before setup finishes a batch only syncs the file list, so names are searchable while the model downloads.
        if (retriever.prefs.value.paused || !graph.platformReady()) return@withContext Result.success()
        try {
            val next = coroutineScope {
                // A sync-only run takes seconds: no notification for it.
                val foreground = retriever.setUp.value == true && retriever.uiVisible &&
                    safeSetForeground(foregroundInfo(retriever.state.value))
                val progress = if (foreground) launch { showProgress() } else null
                retriever.indexBatch(8.minutes).also { progress?.cancel() }
            }
            if (!retriever.prefs.value.paused) schedule(next)
            // Nothing follows soon and nobody is searching: give the model's memory back.
            // DONE also means another worker's batch holds the lock: never close the model under it.
            if (next != NextRun.SOON && !retriever.uiVisible && !retriever.state.value.running) retriever.release()
        } finally {
            // Our own notify() posts outlive the worker when there was no foreground service to own them.
            notificationManager.cancel(notificationId)
        }
        Result.success()
    }

    private fun schedule(next: NextRun) = when (next) {
        NextRun.SOON -> IndexScheduler.continueLater(applicationContext, 10.seconds)
        NextRun.AFTER_COOLDOWN -> IndexScheduler.continueLater(applicationContext, 15.minutes)
        NextRun.WHEN_CHARGING -> IndexScheduler.continueWhenCharging(applicationContext)
        NextRun.WHEN_IDLE -> IndexScheduler.continueWhenIdle(applicationContext)
        NextRun.DONE -> Unit
    }

    private suspend fun showProgress() {
        var last = 0L
        retriever.state.collect { s ->
            val now = SystemClock.elapsedRealtime()
            if (s.running && now - last >= 1000) {
                last = now
                notificationManager.notify(notificationId, notification(s))
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(retriever.state.value)

    /** False when Android refused the foreground service (background start, time limit). */
    private suspend fun safeSetForeground(foregroundInfo: ForegroundInfo): Boolean = try {
        setForeground(foregroundInfo)
        true
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.w("Retriever", "Unable to run as foreground service: ${e.message}")
        false
    }

    private fun notification(s: IndexState) =
        Notifications.progress(applicationContext, s.processed + s.failed, s.pendingAtStart, s.current, pauseIntent)

    // mediaProcessing (API 35) has its own time budget; dataSync's 6 h a day is shared with other work.
    private fun foregroundInfo(s: IndexState) = ForegroundInfo(
        notificationId,
        notification(s),
        if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
}
