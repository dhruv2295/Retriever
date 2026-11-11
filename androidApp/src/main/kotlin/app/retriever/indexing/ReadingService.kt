package app.retriever.indexing

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.retriever.IndexWorker
import app.retriever.core.NextRun
import app.retriever.core.ReadingProgress
import app.retriever.core.Retriever
import app.retriever.core.ThermalRest
import app.retriever.core.indexingDispatcher
import app.retriever.model.Calibrator
import app.retriever.model.ModelRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The foreground service for Start processing: the session the person started, batch after batch until everything is
 * processed, with its progress and Pause in the notification. The visible tap starts it before database or model work
 * (Play's dataSync declaration is for work a person starts); automatic jobs never promote themselves.
 * JobScheduler counts every job that runs past its guarantee (10 minutes, 3 expedited) as timed out, even one that ends by
 * itself, and restricts an app with more than 10 in a day as buggy (JobSchedulerService.maybeProcessBuggyJob). A worker
 * that held WorkManager's foreground service kept its job for 30 minutes at a time: a Pixel 8 Pro on its charger was
 * restricted overnight and then got about one job a day. Google's advice for long workers is the same: start the
 * foreground service directly (develop/background-work/background-tasks/persistent/how-to/long-running).
 */
class ReadingService :
    Service(),
    KoinComponent {
    private val retriever: Retriever by inject()
    private val index: IndexService by inject()
    private val models: ModelRepository by inject()
    private val calibrator: Calibrator by inject()
    private val scope = CoroutineScope(SupervisorJob() + indexingDispatcher)
    private var run: Job? = null // main thread only

    // Main thread only. Stopping by the latest startId keeps a start Android has queued from being stopped with the old run.
    private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val mine = intent?.getLongExtra(TOKEN, 0L) ?: 0L
        val current = index.isCurrent(mine)
        if (!current && run?.isActive == true) return START_NOT_STICKY
        // Android expects startForeground within seconds of startForegroundService, even for a start a later tap superseded.
        val allowed = try {
            ServiceCompat.startForeground(
                this,
                Notifications.PROGRESS_ID,
                Notifications.progress(this, ReadingProgress(), null),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            true
        } catch (e: Exception) {
            Log.w("Retriever", "Unable to run as foreground service: ${e.message}")
            false
        }
        if (!current || !allowed) {
            // Refused (Android 15's 6 h a day used up, or the app left the front): plain jobs carry on.
            if (current) index.follow(Duration.ZERO)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        // A replacement takes over at once; the old run's batch gives back the core lock before the new one reads.
        run?.cancel()
        val setup = intent?.getBooleanExtra(SETUP, false) == true
        run = scope.launch(start = CoroutineStart.UNDISPATCHED) { index.host(mine, setup, ::read) }.also { job ->
            job.invokeOnCompletion { ContextCompat.getMainExecutor(this).execute { if (run === job) stopSelfResult(latestStartId) } }
        }
        return START_NOT_STICKY
    }

    // Android 15+: dataSync's 6 h a day ran out. The cancelled run's cleanup hands the reading to plain jobs.
    override fun onTimeout(startId: Int, fgsType: Int) {
        run?.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Batch after batch while the session lasts; a pause for the battery, heat or Battery Saver waits here, without a wake lock. */
    private suspend fun read(): NextRun = try {
        coroutineScope {
            val progress = launch { showProgress() }
            var next = readBatch()
            while (retriever.prefs.value.started && next != NextRun.RETRY) {
                val blocked = retriever.readingBlocker() != null
                // DONE with nothing read (a failed NPU build waits for the generic download): another batch would do the same.
                if (next == NextRun.DONE && !blocked &&
                    (retriever.state.value.processed == 0 || retriever.finishProcessingRequest(retriever.processingRequestGeneration))
                ) {
                    break
                }
                // A cooldown with nothing to wait out (a model open refused for memory, People's model downloading) rests as a job would.
                if (next == NextRun.AFTER_COOLDOWN && !blocked) {
                    delay(ThermalRest.RECHECK)
                } else if (next != NextRun.SOON || blocked) {
                    do {
                        delay(ThermalRest.POLL)
                    } while (retriever.prefs.value.started && retriever.readingBlocker() != null)
                }
                next = readBatch()
            }
            progress.cancel()
            next
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("Retriever", "Held run failed", e)
        NextRun.RETRY
    }

    private suspend fun readBatch(): NextRun {
        // A known resource wait does no model setup or library scan and holds no CPU wake lock.
        if (retriever.readingBlocker() != null) return NextRun.AFTER_COOLDOWN
        withWakeLock {
            it.acquire(10.minutes.inWholeMilliseconds)
            IndexWorker.prepare(retriever, models, calibrator)
        }
        if (retriever.readingBlocker() != null) return NextRun.AFTER_COOLDOWN
        val session = retriever.readNowSession
        // Taken once the core lock is: waiting for a cancelled batch to give it back needs no CPU.
        val next = withWakeLock { wake ->
            retriever.indexBatch(BATCH, waitForLock = true, expectedSession = session, onLocked = { wake.acquire((BATCH + 1.minutes).inWholeMilliseconds) })
        }
        return if (next == NextRun.DONE && retriever.completeSetupSample(session)) NextRun.SOON else next
    }

    private inline fun <T> withWakeLock(action: (PowerManager.WakeLock) -> T): T {
        val wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Retriever:ReadingBatch")
        try {
            return action(wake)
        } finally {
            if (wake.isHeld) wake.release()
        }
    }

    private suspend fun showProgress() {
        val manager = getSystemService(NotificationManager::class.java)
        var last = 0L
        var shownTitle: String? = null
        combine(retriever.state, retriever.counts(), retriever.readingStatus(), ::Triple).collect { (s, c, status) ->
            val now = SystemClock.elapsedRealtime()
            // While processing, at most once a second; between batches (a phone in use, heat) only when the words change.
            if ((s.running && now - last >= 1000) || status.title != shownTitle) {
                last = now
                shownTitle = status.title
                manager.notify(Notifications.PROGRESS_ID, Notifications.progress(this, c.sampleProgress ?: c.progress, status))
            }
        }
    }

    companion object {
        private const val SETUP = "setup"
        internal const val TOKEN = "start-token"
        private val BATCH = 8.minutes

        /** False when Android refused the start: the app left the front between the tap and the reader. */
        internal fun start(context: Context, token: Long, setup: Boolean): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, ReadingService::class.java).putExtra(TOKEN, token).putExtra(SETUP, setup))
            true
        } catch (e: RuntimeException) {
            // Android can reject a start for background restrictions, exhausted quota or missing FGS permission.
            Log.w("Retriever", "Unable to start the foreground service: ${e.message}")
            false
        }
    }
}
