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
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.retriever.core.Retriever
import app.retriever.core.indexingDispatcher
import app.retriever.indexing.IndexService
import app.retriever.indexing.Notifications
import app.retriever.indexing.ReadingService
import app.retriever.model.Calibrator
import app.retriever.model.ModelRepository
import app.retriever.model.ModelState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
    // A stop (constraints, time limit, Pause) cancels doWork, and with it the batch inside indexBatch:
    // the cancelBatch() the platform's onStopped would call (it is final in CoroutineWorker).
    override suspend fun doWork(): Result = withContext(indexingDispatcher) {
        // Owned threads may stay at background priority; shared IO/Default threads must never be demoted.
        // First, so a paused or not-yet-permitted run keeps watching too. Only the media run, and only its first attempt:
        // any other run (or a retry) appending would grow a chain behind the waiting trigger.
        if (IndexService.MEDIA in tags && runAttemptCount == 0) index.watchMedia(rearm = true)
        // ReadingService's held run reads batch after batch, each syncing first, and schedules what follows. A media or
        // periodic job that took the index lock from it ended its foreground service: a Pixel on its charger went on throttled
        // jobs at 3 a.m. and read nothing more until morning.
        if (index.foregroundHeld()) return@withContext Result.success()
        // A fresh process (WorkManager woke it) has default flows until this read.
        retriever.refreshSetUp()
        val started = TimeSource.Monotonic.markNow()
        val trial = IndexService.TRIAL in tags
        // Before setup finishes a batch only syncs the file list, so names are findable before reading starts.
        if (!platformReady()) {
            // Before access too, which the person may not have given yet; a trial runs in front, where the upgrade does this.
            // No GPU retest yet: it reads the newest photos.
            if (!trial) prepare(retriever, models, calibrator, retestGpu = false)
            retriever.clearUnavailableLibrary()
            return@withContext Result.success()
        }
        // A run that goes on batch after batch holds ReadingService, not this job, which ends now. A trial handoff starts it
        // before waiting for the direct batch to stop. A trial or charger run asks only when IndexService saw an exemption that
        // lets Android start it (FOREGROUND). A run that can't read content now (paused, unplugged, Battery Saver) only syncs
        // names, which needs no notification.
        val ask = inputData.getBoolean(IndexService.FOREGROUND, false)
        if ((ask || (!retriever.state.value.running && retriever.uiVisible)) && retriever.readingBlocker() == null &&
            ReadingService.start(applicationContext, foregroundInfo().notification, trial)
        ) {
            return@withContext Result.success()
        }
        try {
            val next = coroutineScope {
                if (!trial) prepare(retriever, models, calibrator)
                val guard = launch { stopBeforeFreeze() }
                // Inside the job's guarantee, or JobScheduler counts the run as timed out (see ReadingService): trial and charger
                // runs are expedited, with 3 minutes.
                val budget = if (trial || IndexService.CHARGER in tags) EXPEDITED_BUDGET else 8.minutes
                val next = retriever.indexBatch((budget - started.elapsedNow()).coerceAtLeast(10.seconds), waitForLock = trial)
                guard.cancel()
                next
            }
            index.notifySampleIfReady()
            index.notifyCaughtUpIfReady()
            if (!retriever.prefs.value.paused) index.schedule(next)
        } finally {
            if (isStopped && Build.VERSION.SDK_INT >= 31) Log.i("Retriever", "Index run stopped, reason $stopReason")
        }
        // Never Result.retry(): NOW work waiting in WorkManager's backoff (up to 5 h) would make indexNow's KEEP drop every
        // Resume, setting change and first-run commit until it ran. A failed batch rests through CONTINUE instead.
        Result.success()
    }

    /**
     * This worker can run with no job keeping the process alive: WorkManager may run it in-process with no job behind it
     * (GreedyScheduler). Once the app is hidden and holds no foreground service, Android ranks it cached and freezes it 10 s
     * later, mid-batch or mid model open, and nothing restarts it: a Pixel 8 Pro read nothing for hours that way. Stopping
     * the batch first lets the normal follow-up run as a real job.
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

    // dataSync on every level: Play's foreground service declaration lists local file processing under it, while
    // mediaProcessing's only declared use is media transcoding. Android 15+ gives either type the same 6 h a day.
    private suspend fun foregroundInfo() = ForegroundInfo(
        Notifications.PROGRESS_ID,
        Notifications.progress(
            applicationContext,
            retriever.counts().first().let { it.sampleProgress ?: it.progress },
            null,
            Notifications.pauseIntent(applicationContext),
        ),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    internal companion object {
        /** An expedited run's batch: its job's guarantee is 3 minutes, sync and model setup included. */
        val EXPEDITED_BUDGET = 2.minutes + 30.seconds

        /**
         * A model download that finished with the app closed is checked and tuned here, so search by description is ready
         * without opening the app, then a GPU retest that's due runs, so a GPU that passes serves this run. Each step is a
         * no-op once done; a failure is logged and the next run tries again. After any foreground service starts: Android
         * allows a start for a few seconds after leaving, and a hash or tune can take a minute.
         */
        suspend fun prepare(retriever: Retriever, models: ModelRepository, calibrator: Calibrator, retestGpu: Boolean = true) {
            if (!retriever.textOnly) {
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
            if (!retestGpu) return
            try {
                calibrator.retestGpuIfDue()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Retriever", "GPU retest failed", e)
            }
        }
    }
}
