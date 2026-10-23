package app.retriever.core

import android.os.Process
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

private fun procTasks(): List<Int> = File("/proc/self/task").list().orEmpty().mapNotNull { it.toIntOrNull() }

/** A thread's kernel name; null once it has exited. */
private fun procName(tid: Int): String? = try {
    File("/proc/self/task/$tid/comm").readText().trimEnd()
} catch (_: IOException) {
    null
}

private val markers = AtomicInteger()

/**
 * Runs [block] under a marker thread name, then lowers to background priority every thread started meanwhile that
 * still carries it. The model runtime starts its native pools during open and on the first inference, a priority set
 * on the creating thread did not reach them on a Pixel 8 Pro, and a native thread starts with its creator's name. So
 * threads other code started meanwhile (coroutine workers that later run search, Binder, HWUI) and threads that named
 * themselves keep their priority. Threads that exit before they can be lowered are skipped.
 */
fun <T> lowerNewThreads(
    tasks: () -> List<Int> = ::procTasks,
    name: (tid: Int) -> String? = ::procName,
    setPriority: (tid: Int, priority: Int) -> Unit = { tid, priority -> Process.setThreadPriority(tid, priority) },
    block: () -> T,
): T {
    val caller = Thread.currentThread()
    val own = caller.name
    // The kernel keeps 15 characters of a thread name.
    val marker = "rtv-model-${markers.incrementAndGet() % 100_000}"
    val before = tasks().toSet()
    caller.name = marker
    try {
        return block()
    } finally {
        caller.name = own
        for (tid in tasks()) {
            if (tid in before || name(tid) != marker) continue
            try {
                setPriority(tid, Process.THREAD_PRIORITY_BACKGROUND)
            } catch (_: RuntimeException) {
                // exited since the listing, or not ours to change
            }
        }
    }
}
