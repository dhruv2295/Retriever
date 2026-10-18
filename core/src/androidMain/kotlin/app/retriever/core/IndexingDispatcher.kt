package app.retriever.core

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

actual val indexingDispatcher: CoroutineDispatcher by lazy { newIndexingDispatcher() }

/** The app keeps one for its process; isolated hosts can close their executor when finished. */
fun newIndexingDispatcher(): ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { work ->
    Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        work.run()
    }, "Retriever-index").apply { isDaemon = true }
}.asCoroutineDispatcher()
