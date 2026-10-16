package app.rummage

import android.app.Application
import app.rummage.core.prefs
import kotlinx.coroutines.launch

class RummageApp : Application() {
    lateinit var graph: AppGraph

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        Notifications.createChannel(this)
        MediaObserver.register(this)
        graph.scope.launch {
            if (graph.isSetUp()) IndexScheduler.schedulePeriodic(this@RummageApp, graph.store.prefs().onlyWhileCharging)
        }
    }
}
