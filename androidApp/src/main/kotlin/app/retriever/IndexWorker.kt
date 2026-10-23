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
// Adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/smartalbum/SmartAlbumIndexingWorker.kt (Apache-2.0). Modified for Retriever: the per-photo loop, input data, setProgress, coordinator pause and sample branch are replaced by one Retriever.indexBatch, foreground services for visible or expedited trial work, and follow-up scheduling by NextRun.

package app.retriever

import android.app.ActivityManager
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
import app.retriever.core.Retriever
import app.retriever.core.StopReason
import app.retriever.core.indexingDispatcher
import app.retriever.indexing.IndexService
import app.retriever.indexing.Notifications
import app.retriever.indexing.PauseReceiver
import app.retriever.model.Calibrator
import app.retriever.model.ModelRepository
import app.retriever.model.ModelState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// Stays in app.retriever: WorkManager persists this class name in pending work specs.
// Built by Koin's WorkerFactory (worker { } in AppModule), so its dependencies are constructor parameters.
class IndexWorker(
    context: Context,
    params: WorkerParameters,
    private val retriever: Retriever,
    private val index: IndexService,
    private val platformReady: () -> Boolean,
    private val calibrator: Calibrator,
    private val models: ModelRepository,
) : CoroutineWorker(context, params) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    // One id for every run: two runs that overlap (a session and a scheduled run, one waiting for the other's lock) showed two
    // identical notifications when each used its own work id.
    private val notificationId: Int = Notifications.PROGRESS_ID
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
    override suspend fun doWork(): Result = withContext(indexingDispatcher) {
        // Owned threads may stay at background priority; shared IO/Default threads must never be demoted.
        // First, so a paused or not-yet-permitted run keeps watching too. Only the media run, and only its first attempt:
        // any other run (or a retry) appending would grow a chain behind the waiting trigger.
        if (IndexService.MEDIA in tags && runAttemptCount == 0) index.watchMedia(rearm = true)
        // A fresh process (WorkManager woke it) has default flows until this read.
        retriever.refreshSetUp()
        val started = TimeSource.Monotonic.markNow()
        val trial = inputData.getBoolean(IndexService.TRIAL, false)
        // Before access, which the person may not have given yet; a trial runs in front, where the upgrade does this.
        if (!trial) prepareModel()
        // Before setup finishes a batch only syncs the file list, so names are findable before reading starts.
        if (!platformReady()) {
            retriever.clearUnavailableLibrary()
            return@withContext Result.success()
        }
        try {
            val next = coroutineScope {
                // A trial handoff owns its foreground service before waiting for the direct batch to stop. A trial or charger run
                // asks only when IndexService saw an exemption that lets Android start it (FOREGROUND). A run that can't read
                // content now (paused, unplugged, Battery Saver) only syncs names, which needs no notification.
                val ask = inputData.getBoolean(IndexService.FOREGROUND, false)
                val foreground = (ask || (!retriever.state.value.running && retriever.uiVisible)) &&
                    retriever.readingBlocker() == null && safeSetForeground(foregroundInfo())
                val progress = if (foreground) launch { showProgress() } else null
                val guard = launch { stopBeforeFreeze() }
                // Before the batch, so a GPU that passes serves this run; its minute comes out of the batch's budget.
                if (!trial) {
                    try {
                        calibrator.retestGpuIfDue()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("Retriever", "GPU retest failed", e)
                    }
                }
                var next = retriever.indexBatch((8.minutes - started.elapsedNow()).coerceAtLeast(1.minutes), waitForLock = trial)

                // A foreground service has no 10 minute limit (WorkManager stops it at Android 15's FGS timeout): on a
                // charger or in front, batches follow each other here, resting as restBefore says; heat polls until the phone cools.
                // Process for 15 minutes holds the service on battery too, for the session it promised.
                fun holds() = IndexService.holdsForeground(
                    next,
                    retriever.prefs.value.paused,
                    retriever.uiVisible,
                    retriever.deviceState().pluggedIn || retriever.prefs.value.readNowUntil > System.currentTimeMillis(),
                )
                while (foreground && holds()) {
                    val lastStop = retriever.state.value.lastStop
                    delay(IndexService.restBefore(next, lastStop))
                    if (next == NextRun.AFTER_COOLDOWN && lastStop == StopReason.HOT && !retriever.deviceState().cooled) continue
                    if (next == NextRun.WHEN_IDLE && retriever.readingBlocker() == StopReason.BUSY) continue
                    index.notifySampleIfReady()
                    next = retriever.indexBatch(8.minutes)
                }
                progress?.cancel()
                guard.cancel()
                next
            }
            index.notifySampleIfReady()
            index.notifyCaughtUpIfReady()
            if (!retriever.prefs.value.paused) index.schedule(next)
        } finally {
            if (isStopped && Build.VERSION.SDK_INT >= 31) Log.i("Retriever", "Index run stopped, reason $stopReason")
            // Our own notify() posts outlive the worker when there was no foreground service to own them.
            notificationManager.cancel(notificationId)
        }
        // Never Result.retry(): NOW work waiting in WorkManager's backoff (up to 5 h) would make indexNow's KEEP drop every
        // Resume, setting change and first-run commit until it ran. A failed batch rests through CONTINUE instead.
        Result.success()
    }

    /**
     * A model download that finished with the app closed is checked and tuned here, so search by description is ready
     * without opening the app. Each step is a no-op once done; a failure is logged and the next run tries again.
     */
    private suspend fun prepareModel() {
        if (retriever.textOnly) return
        try {
            models.sync(retriever.requiredBuild.value)
            // sync() hashes a finished download in the app's scope; a download still running is left to finish.
            models.state.first { it != ModelState.Checking }
            calibrator.tuneOnce()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Errors too (a missing API, OOM), as the upgrade's own tune catches them.
            Log.w("Retriever", "model setup in the background failed", e)
        }
    }

    /**
     * This worker can run with no job keeping the process alive: WorkManager may run it in-process with no job behind it
     * (GreedyScheduler), and a refused setForeground leaves WorkManager counting it as foreground (Processor.startForeground
     * records it before the start throws), so it ignores the job's stop. IndexService asks for the service only where an
     * exemption applies, which avoids most of the second, but not a start refused after Android 15's 6 h limit. Once the app
     * is hidden and holds no foreground service, Android ranks it cached and freezes it 10 s later, mid-batch or mid model
     * open, and nothing restarts it: a Pixel 8 Pro read nothing for hours that way. Stopping the batch first lets the normal
     * follow-up run as a real job.
     */
    private suspend fun stopBeforeFreeze() {
        while (true) {
            delay(2.seconds)
            val state = ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState)
            if (IndexService.aboutToFreeze(state.importance)) {
                Log.w("Retriever", "Cached with no job or service: stopping the batch before Android freezes the app")
                retriever.cancelBatch()
                return
            }
        }
    }

    // Android 11 implements expedited work as a foreground service and needs its notification before doWork.
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    private suspend fun showProgress() {
        var last = 0L
        var shownTitle: String? = null
        combine(retriever.state, retriever.counts(), retriever.readingStatus(), ::Triple).collect { (s, c, status) ->
            val now = SystemClock.elapsedRealtime()
            // While processing, at most once a second; between batches (a phone in use, heat) only when the words change.
            if ((s.running && now - last >= 1000) || status.title != shownTitle) {
                last = now
                shownTitle = status.title
                notificationManager.notify(notificationId, Notifications.progress(applicationContext, c.sampleProgress ?: c.progress, status, pauseIntent))
            }
        }
    }

    /** False when Android refused the foreground service (background start, time limit). */
    private suspend fun safeSetForeground(foregroundInfo: ForegroundInfo): Boolean = try {
        setForeground(foregroundInfo)
        true
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.w("Retriever", "Unable to run as foreground service: ${e.message}")
        false
    }

    // dataSync on every level: Play's foreground service declaration lists local file processing under it, while
    // mediaProcessing's only declared use is media transcoding. Android 15+ gives either type the same 6 h a day.
    private suspend fun foregroundInfo() = ForegroundInfo(
        notificationId,
        Notifications.progress(applicationContext, retriever.counts().first().let { it.sampleProgress ?: it.progress }, null, pauseIntent),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
}
