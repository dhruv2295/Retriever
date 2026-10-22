package app.retriever.core

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

actual val indexingDispatcher: CoroutineDispatcher by lazy { newIndexingDispatcher() }

/** Extraction read ahead of the embedding file, at the same background priority: a thread per file it may read ahead. */
val readAheadDispatcher: CoroutineDispatcher by lazy { newIndexingDispatcher(ThermalRest.MAX_READ_AHEAD, "Retriever-ahead") }

/** The app keeps one for its process; isolated hosts can close their executor when finished. */
fun newIndexingDispatcher(threads: Int = 1, name: String = "Retriever-index"): ExecutorCoroutineDispatcher = Executors.newFixedThreadPool(threads) { work ->
    Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        work.run()
    }, name).apply { isDaemon = true }
}.asCoroutineDispatcher()

private val exclusiveLock = Any()

internal actual fun <T> exclusive(block: () -> T): T = synchronized(exclusiveLock, block)
