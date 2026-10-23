package app.retriever.indexing

import android.app.ActivityManager
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
            } else if (retriever.deviceState().pluggedIn) {
                // Android defers a cached app's screen-off broadcast and IDLE can take an hour, so a Pixel on its charger
                // sat unread with the screen off. A check that finds the phone still in use stops within one file.
                continueLater(BUSY_RECHECK)
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
        } else if (startsOnCharger(retriever.deviceState().pluggedIn, retriever.readingBlocker(), retriever.hasWork())) {
            // A charger run resting between batches (heat, a refused model open) holds the foreground service: replacing it
            // would end the service, which could not be started again from here.
            if (!chargerRunAlive()) continueOnCharger()
        } else if (retriever.state.value.lastStop == StopReason.BUSY) {
            continueLater(10.seconds)
        }
    }

    suspend fun powerSaveChanged() {
        if (context.getSystemService(PowerManager::class.java).isPowerSaveMode) return
        if (retriever.hasPendingTaste()) continueTrial() else indexNow()
    }

    /** Short user-started work uses expedited WorkManager, avoiding device-idle maintenance windows. */
    internal suspend fun continueTrial() = continueInForeground(TRIAL)

    /**
     * The app just left with the phone on a charger and files to read: a run that asks for a foreground service while
     * Android still allows it (just after leaving a visible state) and holds it for as long as the charger does, batch
     * after batch. Refused, it is an ordinary run with follow-ups, as before.
     */
    internal suspend fun continueOnCharger() = continueInForeground(CHARGER)

    private suspend fun chargerRunAlive() = work.getWorkInfosForUniqueWorkFlow(CONTINUE).first().any { CHARGER in it.tags && !it.state.isFinished }

    private suspend fun continueInForeground(tag: String) {
        val request = OneTimeWorkRequestBuilder<IndexWorker>()
            // Expedited work rejects battery/charging/idle constraints; core checks battery, heat and Battery Saver per file.
            .setInputData(workDataOf(tag to true))
            .addTag(tag)
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

    /** Home card's Notify me, once everything is searchable; not claimed while notifications are off, so it waits for them. */
    suspend fun notifyCaughtUpIfReady() {
        if (!context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()) return
        if (retriever.claimCaughtUpNotice()) Notifications.caughtUp(context, retriever.counts().first().progress.processed, retriever.textOnly)
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
        /** Leaving on a charger with reading allowed (Pause, schedule, heat, power) and something to read starts a foreground run. */
        fun startsOnCharger(pluggedIn: Boolean, blocker: StopReason?, hasWork: Boolean) = pluggedIn && blocker == null && hasWork

        /**
         * A run holding a foreground service starts its next batch at once (or rests from heat in place) while the app shows
         * or the phone charges, unless paused. On a charger it also waits out the phone being in use, so it never hands back to
         * WorkManager's throttled jobs; anything else ends it, and WorkManager schedules what follows.
         */
        fun holdsForeground(next: NextRun, paused: Boolean, visible: Boolean, pluggedIn: Boolean) = !paused && (visible || pluggedIn) &&
            (next == NextRun.SOON || next == NextRun.AFTER_COOLDOWN || (next == NextRun.WHEN_IDLE && pluggedIn))

        /**
         * The wait before a held run's next batch. Heat polls (the run then checks [app.retriever.core.DeviceState.cooled]);
         * a model open refused for memory rests as a WorkManager follow-up would; a model closed mid-batch (SOON with no stop
         * reason) keeps the old 10 s spacing; a phone in use is checked every [BUSY_POLL]; a batch that ran out of time goes on
         * at once.
         */
        fun restBefore(next: NextRun, lastStop: StopReason?): Duration = when {
            next == NextRun.AFTER_COOLDOWN -> if (lastStop == StopReason.HOT) ThermalRest.POLL else ThermalRest.RECHECK
            next == NextRun.WHEN_IDLE -> BUSY_POLL
            lastStop == null -> 10.seconds
            else -> Duration.ZERO
        }

        /** Cached: Android 14+ freezes the process 10 s later, whatever it is running. */
        fun aboutToFreeze(importance: Int) = importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED

        /** How often a phone in use on its charger is checked for being put down. */
        val BUSY_RECHECK = 5.minutes

        /** The same check inside a charger run that holds its foreground service: it carries on within seconds of the screen going off. */
        val BUSY_POLL = 15.seconds

        const val CHARGER = "charger"
        const val TRIAL = "trial"
        const val NOW = "index-now"
        const val CONTINUE = "index-continue"
        const val PERIODIC = "index-periodic"
        const val MEDIA = "index-media"
    }
}
