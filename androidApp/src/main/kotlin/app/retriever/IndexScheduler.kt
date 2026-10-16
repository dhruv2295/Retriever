package app.retriever

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.launch
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
    fun continueWhenCharging(context: Context) =
        continueWhen(context, Constraints.Builder().setRequiresCharging(true).build())

    /** After a batch stopped because the phone was in use; the screen-off receiver usually comes first. */
    fun continueWhenIdle(context: Context) =
        continueWhen(context, Constraints.Builder().setRequiresDeviceIdle(true).build())

    private fun continueWhen(context: Context, constraints: Constraints) {
        val request = OneTimeWorkRequestBuilder<IndexWorker>().setConstraints(constraints).build()
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
        for (name in listOf(NOW, CONTINUE)) WorkManager.getInstance(context).cancelUniqueWork(name)
    }

    /**
     * The user's Pause (Status screen, notification). A running batch, the periodic one too, reads the flag
     * per file and stops within one; every later run returns early until [resumeByUser].
     */
    suspend fun pauseByUser(context: Context) {
        (context.applicationContext as RetrieverApp).graph.retriever.pause()
        pause(context)
    }

    /** "Index now": lifts a user pause and starts a batch. */
    suspend fun resumeByUser(context: Context) {
        (context.applicationContext as RetrieverApp).graph.retriever.resume()
        indexNow(context)
    }
}

/** The notification's "Pause" action. A cancel-by-id intent would cancel the periodic request for good. */
class PauseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as RetrieverApp
        app.graph.scope.launch {
            try {
                IndexScheduler.pauseByUser(app)
            } finally {
                pending.finish()
            }
        }
    }
}
