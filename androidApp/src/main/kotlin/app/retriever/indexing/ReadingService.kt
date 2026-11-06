package app.retriever.indexing

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import app.retriever.IndexWorker
import app.retriever.core.NextRun
import app.retriever.core.ReadingProgress
import app.retriever.core.Retriever
import app.retriever.core.StopReason
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The foreground service for a run that goes on batch after batch: in front, on a charger after leaving, and Read for 15
 * minutes. IndexWorker starts it where Android allows the start and returns at once, so no job outlives a few seconds.
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android expects startForeground within seconds of startForegroundService, so first, with what the worker built.
        val notification = intent?.let { IntentCompat.getParcelableExtra(it, NOTIFICATION, Notification::class.java) }
            ?: Notifications.progress(this, ReadingProgress(), null, Notifications.pauseIntent(this))
        try {
            ServiceCompat.startForeground(this, Notifications.PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            // Android 15's 6 h a day used up: plain jobs take over.
            Log.w("Retriever", "Unable to run as foreground service: ${e.message}")
            index.continueLater(10.seconds)
            stopSelf()
            return START_NOT_STICKY
        }
        if (run?.isActive != true) {
            val trial = intent?.getBooleanExtra(TRIAL, false) == true
            run = scope.launch { hold(trial) }.also { job ->
                job.invokeOnCompletion { ContextCompat.getMainExecutor(this).execute { if (run === job) stopSelf() } }
            }
        }
        return START_NOT_STICKY
    }

    // Android 15+: dataSync's 6 h a day ran out. The service must stop within seconds; plain jobs carry on.
    override fun onTimeout(startId: Int, fgsType: Int) {
        run?.cancel()
        index.continueLater(10.seconds)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun hold(trial: Boolean) {
        try {
            coroutineScope {
                val progress = launch { showProgress() }
                // Undispatched, so a screen-off from here on sees it.
                val held = launch(start = CoroutineStart.UNDISPATCHED) { index.holdForeground() }
                if (!trial) IndexWorker.prepare(retriever, models, calibrator)
                // Waits for a batch still running (a job's, a direct trial's): a lock lost after indexBatch's short wait read as
                // DONE and ended the service.
                var next = retriever.indexBatch(8.minutes, waitForLock = true)

                // On a charger or in front, batches follow each other here, resting as restBefore says; heat polls until the
                // phone cools. Read for 15 minutes holds the service on battery too, for the session it promised.
                fun holds() = IndexService.holdsForeground(
                    next,
                    retriever.prefs.value.paused,
                    retriever.uiVisible,
                    retriever.deviceState().pluggedIn || retriever.prefs.value.readNowUntil > System.currentTimeMillis(),
                )
                while (holds()) {
                    val lastStop = retriever.state.value.lastStop
                    delay(IndexService.restBefore(next, lastStop))
                    if (next == NextRun.AFTER_COOLDOWN && lastStop == StopReason.HOT && !retriever.deviceState().cooled) continue
                    if (next == NextRun.WHEN_IDLE && retriever.readingBlocker() == StopReason.BUSY) continue
                    index.notifySampleIfReady()
                    next = retriever.indexBatch(8.minutes, waitForLock = true)
                }
                progress.cancel()
                // Joined: the follow-up scheduled next must not find this run still held and leave the reading to it.
                held.cancelAndJoin()
                index.notifySampleIfReady()
                index.notifyCaughtUpIfReady()
                if (!retriever.prefs.value.paused) index.schedule(next)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Retriever", "Held run failed", e)
            index.continueLater(ThermalRest.RECHECK)
        }
    }

    private suspend fun showProgress() {
        val manager = getSystemService(NotificationManager::class.java)
        val pause = Notifications.pauseIntent(this)
        var last = 0L
        var shownTitle: String? = null
        combine(retriever.state, retriever.counts(), retriever.readingStatus(), ::Triple).collect { (s, c, status) ->
            val now = SystemClock.elapsedRealtime()
            // While processing, at most once a second; between batches (a phone in use, heat) only when the words change.
            if ((s.running && now - last >= 1000) || status.title != shownTitle) {
                last = now
                shownTitle = status.title
                manager.notify(Notifications.PROGRESS_ID, Notifications.progress(this, c.sampleProgress ?: c.progress, status, pause))
            }
        }
    }

    companion object {
        private const val NOTIFICATION = "notification"
        private const val TRIAL = "trial"

        /** False when Android refused the start (no exemption from the background start limits). */
        fun start(context: Context, notification: Notification, trial: Boolean): Boolean = try {
            val intent = Intent(context, ReadingService::class.java).putExtra(NOTIFICATION, notification).putExtra(TRIAL, trial)
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException on Android 12+.
            Log.w("Retriever", "Unable to start the foreground service: ${e.message}")
            false
        }
    }
}
