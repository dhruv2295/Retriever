package app.retriever.indexing

import android.content.Context
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.retriever.IndexWorker
import app.retriever.core.NextRun
import app.retriever.core.Retriever
import app.retriever.core.ThermalRest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Background reading has one reader at a time and many wake-ups. ReadingService reads after the person taps Start processing
 * or Resume ([start]), or visibly reclaims that persisted human request, batch after batch: Play allows a dataSync foreground
 * service only for work a person starts. Automatic reading (new files, the charger, the demo) is one unique work, [INDEX],
 * which reads a batch per job and queues its own next run. Everything else (new media, the periodic check, the charger
 * coming back, the receivers, setup) only wakes [INDEX] and never reads. Four works that each read fought over the
 * index lock, cancelled each other's batches and left a Pixel reading a third of the night.
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

    /** App visibility may reclaim only an existing human request, never newly discovered automatic work. */
    suspend fun recoverRequested() {
        if (retriever.uiVisible && !foregroundHeld() && !retriever.prefs.value.paused && retriever.prefs.value.processingRequested) start()
    }

    /** A wake-up that runs once [constraints] hold. */
    private fun wakeWhen(name: String, constraints: Constraints) {
        work.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<IndexWorker>().setConstraints(constraints).build())
    }

    /** What a batch's end asks for. A rest is the reader's own next run; a wait for the charger is a wake-up. */
    fun schedule(next: NextRun) {
        when (next) {
            NextRun.SOON -> follow(10.seconds)

            NextRun.AFTER_COOLDOWN, NextRun.RETRY -> follow(ThermalRest.RECHECK)

            // Android's core no longer returns WHEN_IDLE; iOS's self-test still names it.
            NextRun.WHEN_CHARGING, NextRun.WHEN_IDLE -> wakeWhen(CHARGING, storageNotLow().setRequiresCharging(true).build())

            NextRun.DONE -> Unit
        }
    }

    /** Plugged in, or Battery Saver off: whatever held reading back may have let go. */
    fun deviceChanged() {
        if (!context.getSystemService(PowerManager::class.java).isPowerSaveMode) kick()
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

    /**
     * Every Start and Pause takes the next token; Start's rides in ReadingService's intent. A service whose token is no longer
     * the latest was superseded by a later tap, delivered or not, and stops.
     */
    private val token = MutableStateFlow(0L)

    /** The token of the ReadingService run that holds the session; 0 when none does. */
    private val host = MutableStateFlow(0L)

    /** Makes a token check and the core call it guards one step, so a Pause can't land between a newer Start's check and its call. */
    private val commands = Mutex()

    internal fun isCurrent(mine: Long) = mine != 0L && mine == token.value

    fun foregroundHeld() = host.value != 0L

    /** Only the visible tap launches ReadingService; a persisted session with no live service is over. */
    suspend fun endStaleSession() = commands.withLock {
        if (retriever.prefs.value.started && !foregroundHeld()) retriever.endReadNowSession()
    }

    suspend fun pause() {
        val mine = token.updateAndGet { it + 1 }
        Notifications.cancelWaiting(context)
        commands.withLock {
            if (!isCurrent(mine)) return // a Start tapped since wins
            retriever.pause()
            cancel()
        }
    }

    suspend fun start() = start(setup = false)

    /** Access Continue is the only setup action that launches a foreground service. */
    suspend fun startSetup() = start(setup = true)

    /** The tap launches the service before any database work: Android allows the start only for a few seconds after it. */
    private suspend fun start(setup: Boolean) {
        val blocked = retriever.explicitProcessingBlocker() != null
        if (blocked && foregroundHeld()) return // the session already waits for the same thing
        val mine = token.updateAndGet { it + 1 }
        if (blocked) {
            commands.withLock { if (isCurrent(mine)) retriever.requestProcessing(setup) }
            schedule(NextRun.WHEN_CHARGING)
            updateWaitingNotification()
        } else if (ReadingService.start(context, mine, setup)) {
            Notifications.cancelWaiting(context)
        } else {
            kick()
        }
    }

    /** ReadingService's run for start [mine], after its startForeground: the session, read by [read], until it ends or a later tap supersedes it. */
    internal suspend fun host(mine: Long, setup: Boolean, read: suspend () -> NextRun) {
        host.value = mine
        var session = 0L
        var next: NextRun? = null
        try {
            coroutineScope {
                val superseded = launch {
                    token.first { it != mine }
                    this@coroutineScope.cancel()
                }
                session = commands.withLock { if (isCurrent(mine)) retriever.startProcessing(setup) else 0L }
                next = if (session == 0L) {
                    NextRun.WHEN_CHARGING // superseded, or the battery fell below the floor since the tap: the request waits
                } else {
                    Notifications.cancelWaiting(context)
                    // The reader comes back now and then, in case this process dies and takes the service with it.
                    work.enqueueUniqueWork(INDEX, ExistingWorkPolicy.REPLACE, reader(ThermalRest.RECHECK))
                    read()
                }
                superseded.cancel()
            }
        } finally {
            withContext(NonCancellable) {
                if (host.compareAndSet(mine, 0L)) {
                    if (session != 0L) retriever.endReadNowSession(session)
                    // Superseded: the Pause or the newer Start decides what follows.
                    if (isCurrent(mine)) afterSession(next, hosted = session != 0L)
                }
            }
        }
    }

    /** [next] is null when Android ended the service: plain jobs carry on. [hosted]: the session ran, with its reader on watch. */
    private suspend fun afterSession(next: NextRun?, hosted: Boolean) {
        val finished = next == NextRun.DONE && retriever.finishProcessingRequest(retriever.processingRequestGeneration)
        if (finished) Notifications.cancelWaiting(context) else updateWaitingNotification()
        if (hosted) work.cancelUniqueWork(INDEX)
        if (retriever.prefs.value.paused) return
        notifyCaughtUpIfReady()
        // A DONE that left the request unfinished hands it to plain jobs, as Android ending the service does.
        if (next == null || (next == NextRun.DONE && !finished && retriever.prefs.value.processingRequested)) follow(Duration.ZERO) else schedule(next)
    }

    /** "Processing paused · Tap to continue", for a person's request that no session serves; gone once nothing is left or it's paused. */
    @Volatile private var noticePostedFor = -1L

    internal suspend fun updateWaitingNotification() {
        val mine = token.value
        if (foregroundHeld()) return
        try {
            // A failed watched read retries; an optional notice must not hold a session's cleanup.
            val (counts, status) = withTimeoutOrNull(2.seconds) { retriever.counts().first() to retriever.readingStatus().first() } ?: return
            val prefs = retriever.prefs.value
            val done = status.done
            val total = status.total
            val unfinished = counts.progress.unfinished || (total != null && done != null && done < total)
            if (!prefs.processingRequested || prefs.paused || retriever.state.value.running || !unfinished || counts.progress.notNow) {
                Notifications.cancelWaiting(context)
            } else if (token.value == mine && !foregroundHeld()) {
                // Once per request: a notice the person swiped away stays away until they start or pause again.
                if (noticePostedFor == mine && !Notifications.waitingShown(context)) return
                noticePostedFor = mine
                Notifications.recovery(context, counts.progress, status, retriever.explicitProcessingBlocker())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A status notification failure must not prevent continuation or turn completed work into a retry.
            Log.w("Retriever", "Unable to update saved processing progress", e)
        }
    }

    internal companion object {
        /** The one work that reads. Earlier versions' NOW, CONTINUE, IDLE and recovery runs, still queued after an update, only wake it. */
        const val INDEX = "index"
        const val READER = "reader"
        const val PERIODIC = "index-periodic"
        const val MEDIA = "index-media"
        const val CHARGING = "index-charging"
    }
}
