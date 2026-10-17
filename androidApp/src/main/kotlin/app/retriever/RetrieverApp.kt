package app.retriever

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.retriever.core.StopReason
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class RetrieverApp : Application() {
    lateinit var graph: AppGraph

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        val retriever = graph.retriever
        Notifications.createChannel(this)
        MediaObserver.register(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            /** What the app could see when it was last in front; Settings may have changed it since. */
            private var seen: Access? = null

            override fun onStart(owner: LifecycleOwner) {
                retriever.uiVisible = true
                val access = graph.access()
                if (seen != null && seen != access) graph.accessChanged()
                seen = access
                if (retriever.setUp.value == true) graph.scope.launch { retriever.warmSearch() }
                // A batch held back for the charger or a busy phone runs while the user watches, on battery too.
                val held = retriever.state.value.lastStop.let { it == StopReason.UNPLUGGED || it == StopReason.BUSY }
                if (retriever.setUp.value == true && held) IndexScheduler.indexNow(this@RetrieverApp)
                // Picks the setup chain up where it stopped, whichever screen the user comes back to.
                if (graph.setup.ui.value.started) graph.setup.refresh()
            }

            override fun onStop(owner: LifecycleOwner) {
                retriever.uiVisible = false
            }
        })
        // A batch that stopped because the phone was in use (BUSY) resumes once the screen goes off.
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (retriever.state.value.lastStop == StopReason.BUSY) IndexScheduler.continueLater(context, 10.seconds)
                }
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        graph.scope.launch {
            graph.start()
            if (retriever.setUp.value == true) {
                IndexScheduler.schedulePeriodic(this@RetrieverApp, retriever.prefs.value.onlyWhileCharging)
            }
        }
    }

    // In the background with no batch running, the model is the biggest thing a low-memory killer sees.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val retriever = graph.retriever
        if (level >= TRIM_MEMORY_BACKGROUND && !retriever.uiVisible && !retriever.state.value.running) {
            graph.scope.launch { retriever.release() }
        }
    }
}
