package app.retriever

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore

object MediaObserver {
    /** One scan this long after the first change, however many follow: a file that keeps changing can't put it off. */
    private const val DELAY_MS = 60_000L

    fun register(app: RetrieverApp) {
        val handler = Handler(HandlerThread("retriever-media").apply { start() }.looper)
        val run = Runnable {
            // Before the model too: the worker's sync-only batch makes new files searchable by name.
            if (!app.graph.platformReady()) return@Runnable
            val retriever = app.graph.retriever
            // index-now is dropped while a batch runs; the flag makes that batch sync again at the end.
            retriever.notifyChanged()
            IndexScheduler.indexNow(app)
        }
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                if (!handler.hasCallbacks(run)) handler.postDelayed(run, DELAY_MS)
            }
        }
        app.contentResolver.registerContentObserver(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            true,
            observer,
        )
    }
}
