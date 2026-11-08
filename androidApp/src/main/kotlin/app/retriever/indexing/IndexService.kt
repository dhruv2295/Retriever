package app.retriever.indexing

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.retriever.IndexWorker
import app.retriever.core.NextRun
import app.retriever.core.Retriever
import app.retriever.core.StopReason
import app.retriever.core.ThermalRest
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Background reading has one reader at a time and many wake-ups. ReadingService reads where Android lets it start (fast
 * cores); otherwise one unique work, [INDEX], reads a batch per job (slow cores) and queues its own next run. Everything
 * else (new media, the periodic check, the charger or idle coming back, the receivers, setup) only wakes [INDEX] and never
 * reads. Four works that each read fought over the index lock, cancelled each other's batches and left a Pixel reading a
 * third of the night.
 */
class IndexService(private val context: Context, private val retriever: Retriever) {
    private val work get() = WorkManager.getInstance(context)

    /** Every run waits while storage is low, so a batch doesn't start only to stop at once on a full disk (core's DISK_FULL). */
    private fun storageNotLow() = Constraints.Builder().setRequiresStorageNotLow(true)

    private fun reader(delay: Duration = Duration.ZERO) = OneTimeWorkRequestBuilder<IndexWorker>()
        .setInitialDelay(delay.toJavaDuration())
        .setConstraints(storageNotLow().build())
        .addTag(READER)
        .build()

    /** Wakes the reader. KEEP leaves a running or queued reader be; [indexNow] also makes a running batch sync again. */
    internal fun kick() {
        work.enqueueUniqueWork(INDEX, ExistingWorkPolicy.KEEP, reader())
    }

    /** A person asked for reading now: a queued run waiting out a rest is replaced, and a running batch starts over. */
    private fun kickNow() {
        retriever.cancelBatch()
        work.enqueueUniqueWork(INDEX, ExistingWorkPolicy.REPLACE, reader())
    }

    /**
     * The reader's next run, after [delay]. From inside a running reader APPEND queues it behind that run, which REPLACE
     * would cancel; from ReadingService, with no reader left, it simply enqueues.
     */
    fun follow(delay: Duration) {
        work.enqueueUniqueWork(INDEX, ExistingWorkPolicy.APPEND_OR_REPLACE, reader(delay))
    }

    /** A wake-up that runs once [constraints] hold. */
    private fun wakeWhen(name: String, constraints: Constraints) {
        work.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<IndexWorker>().setConstraints(constraints).build())
    }

    /** What a batch's end asks for. A rest is the reader's own next run; a wait for the charger or idle is a wake-up. */
    fun schedule(next: NextRun) {
        when (next) {
            NextRun.SOON -> follow(10.seconds)

            NextRun.AFTER_COOLDOWN, NextRun.RETRY -> follow(ThermalRest.RECHECK)

            NextRun.WHEN_CHARGING -> wakeWhen(CHARGING, storageNotLow().setRequiresCharging(true).build())

            // In use on battery. The screen-off receiver usually wakes it first, while the app is alive; with the app gone,
            // plugging in wakes it too, as on a charger in use is no reason to wait.
            NextRun.WHEN_IDLE -> {
                wakeWhen(IDLE, storageNotLow().setRequiresDeviceIdle(true).build())
                wakeWhen(CHARGING, storageNotLow().setRequiresCharging(true).build())
            }

            NextRun.DONE -> Unit
        }
    }

    /**
     * ReadingService may start from here: in front, Android 11 (no background start limit), or battery optimization off
     * (develop/background-work/services/fgs/restrictions-bg-start). Elsewhere Android refuses it. Hidden, not for an hour
     * after Android refused it ([foregroundRefused]): every follow-up would start it, be refused and read nothing.
     */
    fun mayStartForeground() = retriever.uiVisible ||
        (
            SystemClock.elapsedRealtime() - refusedAt > REFUSAL_REST.inWholeMilliseconds &&
                (
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
                    )
            )

    @Volatile private var refusedAt = -REFUSAL_REST.inWholeMilliseconds

    /** Android 15+ ended or refused the service: dataSync's 6 hours a day are used up until the app comes to the front. */
    fun foregroundRefused() {
        refusedAt = SystemClock.elapsedRealtime()
        follow(10.seconds)
    }

    /** Plugged in, the screen off after being in use, or Battery Saver off: whatever held reading back may have let go. */
    fun deviceChanged() {
        if (!context.getSystemService(PowerManager::class.java).isPowerSaveMode) kick()
    }

    fun screenOff() {
        if (retriever.state.value.lastStop == StopReason.BUSY) kick()
    }

    // Counted, not a flag: a service start that overlaps the end of the previous hold.
    private val foregroundRuns = AtomicInteger()

    /** ReadingService reads and schedules by itself: a reader that wakes meanwhile leaves it be. */
    fun foregroundHeld() = foregroundRuns.get() > 0

    /** ReadingService's, from the moment it holds the foreground service until it cancels this. */
    suspend fun holdForeground() {
        foregroundRuns.incrementAndGet()
        try {
            awaitCancellation()
        } finally {
            foregroundRuns.decrementAndGet()
        }
    }

    /** "Index now" and changed inputs. */
    fun indexNow() {
        retriever.notifyChanged()
        kick()
    }

    /**
     * Runs a batch after MediaStore changes, even with the app gone: JobScheduler holds the trigger, not this process.
     * One run a minute after the first change however many follow, and within 10 minutes when changes never stop.
     * The app arms it with KEEP (once is enough); a media run re-arms with APPEND_OR_REPLACE, which queues the next
     * trigger behind itself, where KEEP would drop it and REPLACE would cancel the run.
     */
    fun watchMedia(rearm: Boolean = false) {
        val constraints = storageNotLow()
            .addContentUriTrigger(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), true)
            .setTriggerContentUpdateDelay(1.minutes.toJavaDuration())
            .setTriggerContentMaxDelay(10.minutes.toJavaDuration())
            .build()
        val request = OneTimeWorkRequestBuilder<IndexWorker>().setConstraints(constraints).addTag(MEDIA).build()
        work.enqueueUniqueWork(MEDIA, if (rearm) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    fun schedulePeriodic(onlyWhileCharging: Boolean) {
        val constraints = storageNotLow()
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

    /** Stops the reader and its queued runs; the wake-ups stay armed and find reading paused. */
    fun cancel() {
        retriever.cancelBatch()
        work.cancelUniqueWork(INDEX)
    }

    /** Changed settings apply at once: the running batch stops within one file and a fresh run starts. */
    fun restart() = kickNow()

    /** The user's Pause: a running batch stops within one file, and later runs wait until [resume]. */
    suspend fun pause() {
        retriever.pause()
        cancel()
    }

    /** "Index now": lifts a user pause and starts a batch. */
    suspend fun resume() {
        retriever.resume()
        kickNow()
    }

    /** Read for 15 minutes, from Home's line, Reading status or Settings: core's line says so from the tap. */
    suspend fun readNow() {
        retriever.readNow()
        kickNow()
    }

    /** Stop on a Read for 15 minutes: the running batch stops within one file, and the person's own schedule takes over. */
    suspend fun endReadNow() {
        retriever.endReadNowSession()
        restart()
    }

    internal companion object {
        /**
         * A run holding a foreground service starts its next batch at once (or rests from heat in place) while the app shows
         * ([visible] also covers an unfinished first-run sample) or the phone charges, unless paused. On a charger it also waits out the phone
         * being in use, so it never hands back to WorkManager's throttled jobs; anything else ends it, and the reader follows.
         */
        fun holdsForeground(next: NextRun, paused: Boolean, visible: Boolean, pluggedIn: Boolean) = !paused && (visible || pluggedIn) &&
            (next == NextRun.SOON || next == NextRun.AFTER_COOLDOWN || (next == NextRun.WHEN_IDLE && pluggedIn))

        /**
         * The wait before a held run's next batch. Heat polls (the run then checks [app.retriever.core.DeviceState.cooled]);
         * a model open refused for memory rests as the reader would; a model closed mid-batch (SOON with no stop reason) keeps
         * the old 10 s spacing; a phone in use is checked every [BUSY_POLL]; a batch that ran out of time goes on at once.
         */
        fun restBefore(next: NextRun, lastStop: StopReason?): Duration = when {
            next == NextRun.AFTER_COOLDOWN -> if (lastStop == StopReason.HOT) ThermalRest.POLL else ThermalRest.RECHECK
            next == NextRun.WHEN_IDLE -> BUSY_POLL
            lastStop == null -> 10.seconds
            else -> Duration.ZERO
        }

        /** How often a held run checks a phone in use on its charger for being put down. */
        val BUSY_POLL = 15.seconds

        val REFUSAL_REST = 1.hours

        /** The one work that reads. Earlier versions' NOW and CONTINUE runs, still queued after an update, only wake it. */
        const val INDEX = "index"
        const val READER = "reader"
        const val PERIODIC = "index-periodic"
        const val MEDIA = "index-media"
        const val CHARGING = "index-charging"
        const val IDLE = "index-idle"
    }
}
