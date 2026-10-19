package app.retriever.core

import android.os.Process
import java.io.File

private fun procTasks(): List<Int> = File("/proc/self/task").list().orEmpty().mapNotNull { it.toIntOrNull() }

/**
 * Runs [block], then lowers every thread it started to background priority. The model runtime starts its native pools
 * during open and on the first inference, and a priority set on the creating thread did not reach them on a Pixel 8 Pro.
 * Threads that exit before they can be lowered are skipped.
 */
fun <T> lowerNewThreads(
    tasks: () -> List<Int> = ::procTasks,
    setPriority: (tid: Int, priority: Int) -> Unit = { tid, priority -> Process.setThreadPriority(tid, priority) },
    block: () -> T,
): T {
    val before = tasks().toSet()
    try {
        return block()
    } finally {
        for (tid in tasks()) {
            if (tid in before) continue
            try {
                setPriority(tid, Process.THREAD_PRIORITY_BACKGROUND)
            } catch (_: RuntimeException) {
                // exited since the listing, or not ours to change
            }
        }
    }
}
