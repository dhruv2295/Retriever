package app.rummage

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore

object MediaObserver {
    private const val DEBOUNCE_MS = 60_000L

    fun register(app: RummageApp) {
        // Its own thread: isSetUp() reads the database, which must stay off the main thread.
        val handler = Handler(HandlerThread("rummage-media").apply { start() }.looper)
        val run = Runnable {
            if (!app.graph.isSetUp()) return@Runnable
            // index-now is dropped while a batch runs; the flag makes that batch sync again at the end.
            app.graph.changedSinceSync = true
            IndexScheduler.indexNow(app)
        }
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                handler.removeCallbacks(run)
                handler.postDelayed(run, DEBOUNCE_MS)
            }
        }
        app.contentResolver.registerContentObserver(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            true,
            observer,
        )
    }
}
