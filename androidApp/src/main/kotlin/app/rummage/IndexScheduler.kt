package app.rummage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.rummage.core.prefs
import app.rummage.core.setPrefs
import kotlinx.coroutines.launch
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.toJavaDuration

object IndexScheduler {
    private const val NOW = "index-now"
    private const val CONTINUE = "index-continue"
    private const val PERIODIC = "index-periodic"

    fun indexNow(context: Context) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<IndexWorker>().build())
    }

    fun continueLater(context: Context, delay: Duration) {
        val request = OneTimeWorkRequestBuilder<IndexWorker>().setInitialDelay(delay.toJavaDuration()).build()
        WorkManager.getInstance(context).enqueueUniqueWork(CONTINUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** After a batch stopped for being unplugged: Android starts this as soon as the charger is back. */
    fun continueWhenCharging(context: Context) {
        val request = OneTimeWorkRequestBuilder<IndexWorker>()
            .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(CONTINUE, ExistingWorkPolicy.REPLACE, request)
    }

    fun schedulePeriodic(context: Context, onlyWhileCharging: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresCharging(onlyWhileCharging)
            .build()
        val request = PeriodicWorkRequestBuilder<IndexWorker>(6, TimeUnit.HOURS).setConstraints(constraints).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Stops the current and follow-up runs; the periodic run stays scheduled. */
    fun pause(context: Context) {
        for (name in listOf(NOW, CONTINUE)) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(name)
            } catch (e: IllegalStateException) {
                // WorkManager may not be initialized in certain test environments.
            }
        }
    }

    /**
     * The user's Pause (Status screen, notification). Stops any running batch, the periodic one too,
     * and holds until [resumeByUser]: every run returns early while prefs.paused is set. Off the main thread.
     */
    fun pauseByUser(context: Context) {
        val store = (context.applicationContext as RummageApp).graph.store
        val prefs = store.prefs().copy(paused = true)
        store.setPrefs(prefs)
        pause(context)
        // Cancelling stops a running periodic batch; enqueueing again keeps it scheduled (UPDATE
        // replaces a cancelled request with a new one). Its first run sees paused and returns.
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        schedulePeriodic(context, prefs.onlyWhileCharging)
    }

    /** "Index now": lifts a user pause and starts a batch. Off the main thread. */
    fun resumeByUser(context: Context) {
        val store = (context.applicationContext as RummageApp).graph.store
        store.prefs().let { if (it.paused) store.setPrefs(it.copy(paused = false)) }
        indexNow(context)
    }

}

/** The notification's "Pause" action. A cancel-by-id intent would cancel the periodic request for good. */
class PauseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as RummageApp
        app.graph.scope.launch {
            try {
                IndexScheduler.pauseByUser(app)
            } finally {
                pending.finish()
            }
        }
    }
}
