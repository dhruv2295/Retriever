package app.retriever.indexing

import android.content.Context
import android.os.PowerManager
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import app.retriever.IndexWorker
import app.retriever.core.NextRun
import app.retriever.core.Retriever
import app.retriever.core.StopReason
import app.retriever.core.ThermalRest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** Background indexing: the one owner of IndexWorker's unique work, for the screens, the worker and the receivers. */
class IndexService(private val context: Context, private val retriever: Retriever) {
    private val work get() = WorkManager.getInstance(context)
    private val trialLock = Mutex()

    // Serializes ownership changes, not extraction: leaving must persist the successor before cancelling the direct job.
    private val ownership = Mutex()
    private var directTrial: Job? = null

    /** A comeback leaves an already-persisted trial worker in charge; otherwise it reads directly. */
    suspend fun readTrial(): Boolean = trialLock.withLock {
        coroutineScope {
            val reading = ownership.withLock {
                if (!retriever.uiVisible || !retriever.hasPendingTaste()) return@coroutineScope false
                if (work.getWorkInfosForUniqueWorkFlow(CONTINUE).first().any { TRIAL in it.tags && !it.state.isFinished }) {
                    return@coroutineScope true
                }
                retriever.cancelBatch()
                launch(start = CoroutineStart.LAZY) {
                    var next: NextRun
                    do {
                        next = retriever.taste()
                    } while (next == NextRun.SOON && retriever.uiVisible && retriever.hasPendingTaste())
                    ownership.withLock { if (isActive) schedule(next) }
                }.also {
                    directTrial = it
                    it.start()
                }
            }
            try {
                reading.join()
            } finally {
                ownership.withLock { if (directTrial === reading) directTrial = null }
            }
            true
        }
    }

    suspend fun schedule(next: NextRun) {
        when (next) {
            NextRun.SOON -> if (retriever.hasPendingTaste()) continueTrial() else continueLater(10.seconds)

            NextRun.AFTER_COOLDOWN, NextRun.RETRY -> continueLater(ThermalRest.RECHECK)

            NextRun.WHEN_CHARGING -> continueWhenCharging()

            NextRun.WHEN_IDLE -> if (retriever.hasPendingTaste() && !context.getSystemService(PowerManager::class.java).isInteractive) {
                continueTrial()
            } else {
                continueWhenIdle()
            }

            NextRun.DONE -> Unit
        }
    }

    suspend fun screenOff() {
        ownership.withLock {
            if (directTrial?.isActive == true && (!retriever.uiVisible || !context.getSystemService(PowerManager::class.java).isInteractive)) {
                continueTrial()
                directTrial?.cancel()
                return
            }
        }
        if (retriever.state.value.running) return
        if (retriever.hasPendingTaste()) {
            continueTrial()
        } else if (retriever.state.value.lastStop == StopReason.BUSY) {
            continueLater(10.seconds)
        }
    }

    suspend fun powerSaveChanged() {
        if (context.getSystemService(PowerManager::class.java).isPowerSaveMode) return
        if (retriever.hasPendingTaste()) continueTrial() else indexNow()
    }

    /** Short user-started work uses expedited WorkManager, avoiding device-idle maintenance windows. */
    internal suspend fun continueTrial() {
        val request = OneTimeWorkRequestBuilder<IndexWorker>()
            // Expedited work rejects battery/charging/idle constraints; core checks battery, heat and Battery Saver per file.
            .setInputData(workDataOf(TRIAL to true))
            .addTag(TRIAL)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        work.enqueueUniqueWork(CONTINUE, ExistingWorkPolicy.REPLACE, request).await()
    }

    /** KEEP drops this while a batch runs; the change flag makes that batch sync again at its end instead. */
    fun indexNow() {
        retriever.notifyChanged()
        work.enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<IndexWorker>().build())
    }

    // Every follow-up REPLACEs: the last decision wins, so a timed run never waits behind a charger- or idle-gated one.
    // Called from a running follow-up, it cancels that worker too, once its batch is over.
    fun continueLater(delay: Duration) {
        val request = OneTimeWorkRequestBuilder<IndexWorker>().setInitialDelay(delay.toJavaDuration()).build()
        work.enqueueUniqueWork(CONTINUE, ExistingWorkPolicy.REPLACE, request)
    }

    /** After a batch stopped for being unplugged: Android starts this as soon as the charger is back. */
    fun continueWhenCharging() = continueWhen(Constraints.Builder().setRequiresCharging(true).build())

    /** After a batch stopped because the phone was in use; the screen-off receiver usually comes first. */
    fun continueWhenIdle() = continueWhen(Constraints.Builder().setRequiresDeviceIdle(true).build())

    private fun continueWhen(constraints: Constraints) {
        val request = OneTimeWorkRequestBuilder<IndexWorker>().setConstraints(constraints).build()
        work.enqueueUniqueWork(CONTINUE, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Runs a batch after MediaStore changes, even with the app gone: JobScheduler holds the trigger, not this process.
     * One run a minute after the first change however many follow, and within 10 minutes when changes never stop.
     * The app arms it with KEEP (once is enough); a media run re-arms with APPEND_OR_REPLACE, which queues the next
     * trigger behind itself, where KEEP would drop it and REPLACE would cancel the run.
     */
    fun watchMedia(rearm: Boolean = false) {
        val constraints = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), true)
            .setTriggerContentUpdateDelay(1.minutes.toJavaDuration())
            .setTriggerContentMaxDelay(10.minutes.toJavaDuration())
            .build()
        val request = OneTimeWorkRequestBuilder<IndexWorker>().setConstraints(constraints).addTag(MEDIA).build()
        work.enqueueUniqueWork(MEDIA, if (rearm) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    fun schedulePeriodic(onlyWhileCharging: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresCharging(onlyWhileCharging)
            .build()
        val request = PeriodicWorkRequestBuilder<IndexWorker>(6, TimeUnit.HOURS).setConstraints(constraints).build()
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** The first run's demo is done; for a person who left the app while it ran. */
    fun notifyDemoDone(done: Int, planned: Int) = Notifications.demoDone(context, done, planned)

    suspend fun notifySampleIfReady() {
        if (retriever.uiVisible || !context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()) return
        val sample = retriever.tasteProgress().first()
        if (sample.planned > 0 && sample.pending == 0 && retriever.claimSampleNotification()) notifyDemoDone(sample.done, sample.planned)
    }

    /** Stops the current and follow-up runs; the periodic run stays scheduled. */
    fun cancel() {
        for (name in listOf(NOW, CONTINUE)) work.cancelUniqueWork(name)
    }

    /**
     * Changed settings apply at once: the running batch stops within one file and comes back SOON, which queues its
     * follow-up, and a fresh run starts when none is running.
     */
    fun restart() {
        retriever.cancelBatch()
        indexNow()
    }

    /** The user's Pause: a running batch stops within one file, and later runs wait until [resume]. */
    suspend fun pause() {
        retriever.pause()
        cancel()
    }

    /** "Index now": lifts a user pause and starts a batch. */
    suspend fun resume() {
        retriever.resume()
        indexNow()
    }

    /** Read for 15 minutes, from Home's line, Reading status or Settings: core's line says so from the tap. */
    suspend fun readNow() {
        retriever.readNow()
        indexNow()
    }

    /** Stop on a Read for 15 minutes: the running batch stops within one file, and the person's own schedule takes over. */
    suspend fun endReadNow() {
        retriever.endReadNowSession()
        restart()
    }

    internal companion object {
        const val TRIAL = "trial"
        const val NOW = "index-now"
        const val CONTINUE = "index-continue"
        const val PERIODIC = "index-periodic"
        const val MEDIA = "index-media"
    }
}
