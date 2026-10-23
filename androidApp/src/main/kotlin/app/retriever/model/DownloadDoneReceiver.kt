package app.retriever.model

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.retriever.indexing.IndexService
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * DownloadManager finished the model with the app closed: a run checks and tunes it ([app.retriever.IndexWorker]). The
 * download's id isn't compared: the run reads the real state, so any other download only costs a quick check.
 */
class DownloadDoneReceiver :
    BroadcastReceiver(),
    KoinComponent {
    override fun onReceive(context: Context, intent: Intent) {
        get<IndexService>().indexNow()
    }
}
