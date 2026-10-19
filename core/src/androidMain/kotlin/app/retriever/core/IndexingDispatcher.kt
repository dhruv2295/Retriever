package app.retriever.core

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

actual val indexingDispatcher: CoroutineDispatcher by lazy { newIndexingDispatcher() }

/** Synchronous native creation: pools inherit nice; paused indexing leaves idle CPU capacity available to queries. */
fun <T> withBackgroundPriority(block: () -> T): T {
    val before = Process.getThreadPriority(Process.myTid())
    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
    return try {
        block()
    } finally {
        Process.setThreadPriority(before)
    }
}

/** The app keeps one for its process; isolated hosts can close their executor when finished. */
fun newIndexingDispatcher(): ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { work ->
    Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        work.run()
    }, "Retriever-index").apply { isDaemon = true }
}.asCoroutineDispatcher()
