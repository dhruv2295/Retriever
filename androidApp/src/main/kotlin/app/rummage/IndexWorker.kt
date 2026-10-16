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
// Adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/smartalbum/SmartAlbumIndexingWorker.kt (Apache-2.0). Modified for Rummage: the per-photo loop, input data, setProgress, coordinator pause and sample branch are replaced by LibrarySync plus one IndexEngine batch, a lock against overlapping runs, and follow-up scheduling by stop reason.

package app.rummage

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import app.rummage.core.EmbedderClosedException
import app.rummage.core.IndexEngine
import app.rummage.core.StopReason
import app.rummage.core.prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val graph = (applicationContext as RummageApp).graph
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

    override suspend fun doWork(): Result {
        if (!graph.isSetUp()) return Result.success()
        // The spike runs its own copy of the model; a second one would skew it and risk the LMK.
        if (graph.spikeActive) return Result.success()
        if (graph.store.prefs().paused) {
            graph.indexState.update { it.copy(running = false, current = null, lastStop = "PAUSED") }
            return Result.success()
        }
        // index-now, index-continue and index-periodic can all fire at once; only one batch runs.
        // A batch that was just cancelled (pause, then restart) releases the lock within seconds, so wait
        // briefly; a cancelled batch never reaches its DONE check and would drop this request. A batch
        // still running after a minute sees the flag and syncs again when it finishes.
        val locked = graph.indexLock.tryLock() ||
            withTimeoutOrNull(60.seconds) { graph.indexLock.lock(); true } == true
        if (!locked) {
            graph.changedSinceSync = true
            return Result.success()
        }
        try {
            return withContext(Dispatchers.IO) { run() }
        } catch (e: CancellationException) {
            // Only the app cancels for a pause; the system stops work for time limits and constraints.
            val paused = stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP
            graph.indexState.update { it.copy(running = false, current = null, lastStop = if (paused) "PAUSED" else "BUDGET") }
            throw e
        } catch (e: Exception) {
            Log.e("Rummage", "Indexing failed", e)
            // A closed embedder stays cached until released; the retry opens a fresh one.
            if (e is EmbedderClosedException) graph.releaseEmbedder()
            graph.indexState.update {
                it.copy(running = false, current = null, lastError = "Something went wrong reading your files. Rummage will try again.")
            }
            return Result.retry()
        } finally {
            graph.indexLock.unlock()
            // Our own notify() posts outlive the worker when there was no foreground service to own them.
            notificationManager.cancel(notificationId)
        }
    }

    private suspend fun run(): Result {
        val foreground = safeSetForeground(foregroundInfo(0, 0, null))
        graph.changedSinceSync = false
        graph.indexState.update { IndexState(running = true, lastStop = it.lastStop) }
        val synced = graph.sync.sync()
        if (isStopped) return Result.success()

        val store = graph.store
        val prefs = store.prefs()
        val profile = graph.effectiveProfile()!!
        val total = store.countsByState()["pending"] ?: 0L
        graph.indexState.update { it.copy(pendingAtStart = total) }

        var lastNotify = 0L
        val engine = IndexEngine(
            store,
            graph.extractor,
            graph.embedder(),
            graph.monitor,
            profile,
            onFile = { metric ->
                if (!metric.ok) {
                    Log.w("Rummage", "Couldn't index ${metric.file.kind} ${metric.file.name} (${metric.file.mime}, ${metric.file.size} B): ${metric.error}", metric.cause)
                }
                val s = graph.indexState.updateAndGet {
                    it.copy(
                        processed = it.processed + if (metric.ok) 1 else 0,
                        failed = it.failed + if (metric.ok) 0 else 1,
                        current = metric.file.name,
                    )
                }
                val now = SystemClock.elapsedRealtime()
                if (foreground && now - lastNotify >= 1000) {
                    lastNotify = now
                    notificationManager.notify(notificationId, notification(s.processed + s.failed, total, s.current))
                }
            },
            // Read per file, so a switch flipped in Status applies to this batch.
            kinds = { store.prefs().kinds },
            currentProfile = { graph.effectiveProfile() ?: profile },
        )
        val result = engine.runBatch(8.minutes, requireCharging = prefs.onlyWhileCharging, pauseWhenHot = true)
        if (synced.added + synced.changed + synced.removed + result.processed + result.failed > 0) {
            graph.notifyIndexChanged()
        }

        when (result.stopReason) {
            StopReason.BUDGET -> IndexScheduler.continueLater(applicationContext, 10.seconds)
            StopReason.HOT -> IndexScheduler.continueLater(applicationContext, 15.minutes)
            // ponytail: a trigger landing between this check and unlock is missed until the next run.
            StopReason.DONE -> if (graph.changedSinceSync) IndexScheduler.continueLater(applicationContext, 10.seconds)
            // Waiting six hours for the periodic run left indexing stuck after a short unplug.
            StopReason.UNPLUGGED -> IndexScheduler.continueWhenCharging(applicationContext)
        }
        graph.indexState.update {
            it.copy(running = false, current = null, lastStop = result.stopReason.name, lastError = null)
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0, null)

    /** False when Android refused the foreground service (background start, dataSync time limit). */
    private suspend fun safeSetForeground(foregroundInfo: ForegroundInfo): Boolean = try {
        setForeground(foregroundInfo)
        true
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.w("Rummage", "Unable to run as foreground service: ${e.message}")
        false
    }

    private fun notification(processed: Int, total: Long, current: String?) = Notifications.progress(
        applicationContext,
        processed,
        total,
        current,
        pauseIntent,
    )

    private fun foregroundInfo(processed: Int, total: Long, current: String?) = ForegroundInfo(
        notificationId,
        notification(processed, total, current),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
}
