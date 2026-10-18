package app.retriever.indexing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.retriever.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/** The notification's "Pause" action. A cancel-by-id intent would cancel the periodic request for good. */
class PauseReceiver :
    BroadcastReceiver(),
    KoinComponent {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        get<CoroutineScope>(AppScope).launch {
            try {
                get<IndexService>().pause()
            } finally {
                pending.finish()
            }
        }
    }
}
