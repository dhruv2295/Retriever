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
// Adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/smartalbum/SmartAlbumIndexingWorker.kt (Apache-2.0). Modified for Retriever: the per-photo loop, input data, setProgress, coordinator pause and sample branch are replaced by one Retriever.indexBatch, ReadingService where Android allows it, wake-up runs that only start the reader, and follow-up scheduling by NextRun.

package app.retriever

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
import kotlinx.coroutines.flow.first
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
    //
    // WorkManager may start a run in-process before its JobScheduler job (GreedyScheduler). Android can freeze a cached app
    // meanwhile, but the job, once started, attaches to the running work and keeps the process up until it ends
    // (Processor.startWork adds its token, SystemJobService holds the job): the run resumes. Stopping such runs to dodge the
    // freeze stopped batches a job would have kept, then queued another in-process run that was stopped too.
    override suspend fun doWork(): Result = withContext(indexingDispatcher) {
        // Owned threads may stay at background priority; shared IO/Default threads must never be demoted.
        if (IndexService.READER !in tags) {
            // A wake-up never reads. Only the media run's first attempt re-arms: a retry appending would grow a chain behind
            // the waiting trigger. indexNow, so a batch already past its sync syncs again for the new photo.
            if (IndexService.MEDIA in tags && runAttemptCount == 0) index.watchMedia(rearm = true)
            index.indexNow()
            return@withContext Result.success()
        }
        // ReadingService reads batch after batch, each syncing first, and schedules what follows.
        if (index.foregroundHeld()) return@withContext Result.success()
        // A fresh process (WorkManager woke it) has default flows until this read.
        retriever.refreshSetUp()
        val started = TimeSource.Monotonic.markNow()
        // Before setup finishes a batch only syncs the file list, so names are findable before reading starts.
        if (!platformReady()) {
            // Before access too, which the person may not have given yet; the sample runs in front, where the upgrade does
            // this. No GPU retest yet: it reads the newest photos.
            if (!retriever.hasPendingTaste()) prepare(retriever, models, calibrator, retestGpu = false)
            retriever.clearUnavailableLibrary()
            // A tune takes a minute, and the sample's wake-up meanwhile was dropped (KEEP): go on to read it if setup finished.
            if (!platformReady()) return@withContext Result.success()
        }
        val sample = retriever.hasPendingTaste()
        // Where Android allows it, ReadingService reads on the fast cores and this job ends now. A run that can't read
        // content now (paused, unplugged, Battery Saver) only syncs names, which needs no notification.
        if (index.mayStartForeground() && retriever.readingBlocker() == null &&
            ReadingService.start(applicationContext, foregroundInfo().notification, sample)
        ) {
            return@withContext Result.success()
        }
        try {
            if (!sample) prepare(retriever, models, calibrator)
            // Inside the job's 10-minute guarantee, or JobScheduler counts the run as timed out (see ReadingService).
            val next = retriever.indexBatch((BUDGET - started.elapsedNow()).coerceAtLeast(10.seconds), waitForLock = true)
            index.notifySampleIfReady()
            index.notifyCaughtUpIfReady()
            if (!retriever.prefs.value.paused) index.schedule(next)
        } finally {
            if (isStopped && Build.VERSION.SDK_INT >= 31) Log.w("Retriever", "Index run stopped, reason $stopReason")
        }
        // Never Result.retry(): a reader waiting in WorkManager's backoff (up to 5 h) would make kick's KEEP drop every wake-up
        // until it ran. A failed batch rests through the reader's own next run instead.
        Result.success()
    }

    // Nothing is expedited now, but an update can leave an earlier version's expedited run queued: Android 11 runs one as a
    // foreground service and needs its notification before doWork.
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
        /** A run's time, sync and model setup included, with room inside the job's 10-minute guarantee. */
        val BUDGET = 8.minutes

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
