package app.retriever.core

import android.os.Process
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

private fun procTasks(): List<Int> = File("/proc/self/task").list().orEmpty().mapNotNull { it.toIntOrNull() }

private fun commOf(tid: Int): String? = runCatching { File("/proc/self/task/$tid/comm").readText().trim() }.getOrNull()

/** ART's kernel name for a Java thread: names of 15+ characters with a dot keep their end, others their start. */
private fun comm(name: String) = if (name.length < 15 || '@' in name || '.' !in name) name.take(15) else name.takeLast(15)

private fun javaThreadComms(): Set<String> {
    var root = Thread.currentThread().threadGroup ?: return emptySet()
    while (true) root = root.parent ?: break
    val threads = arrayOfNulls<Thread>(root.activeCount() * 2 + 16)
    val n = root.enumerate(threads, true)
    return (0 until n).mapNotNullTo(HashSet()) { threads[it]?.name?.let(::comm) }
}

private fun renameCurrent(name: String): String = Thread.currentThread().let { t -> t.name.also { t.name = name } }

private val markers = AtomicInteger()

/** HWUI's threads are native but draw the UI. */
private val RENDERER = listOf("RenderThread", "hwuiTask")

/**
 * Runs [block], then lowers to background priority the threads the model runtime started during it. The runtime starts
 * its native pools during open and on the first inference, and a priority set on the creating thread did not reach them
 * on a Pixel 8 Pro. Ownership: [block] runs under a marker thread name, which Linux copies to every thread it starts,
 * so the runtime's unnamed pools carry it. Other new threads are lowered only when native and not the renderer's (GPU
 * driver threads name themselves); threads the JVM runs (coroutine workers, Binder) are shared and keep their priority.
 * Threads that exit before they can be lowered are skipped.
 */
fun <T> lowerNewThreads(
    tasks: () -> List<Int> = ::procTasks,
    nameOf: (tid: Int) -> String? = ::commOf,
    javaNames: () -> Set<String> = ::javaThreadComms,
    rename: (String) -> String = ::renameCurrent,
    setPriority: (tid: Int, priority: Int) -> Unit = { tid, priority -> Process.setThreadPriority(tid, priority) },
    block: () -> T,
): T {
    val before = tasks().toSet()
    val marker = "rtv-model-${markers.incrementAndGet() % 100_000}"
    val name = rename(marker)
    try {
        return block()
    } finally {
        rename(name)
        val java = javaNames()
        for (tid in tasks()) {
            if (tid in before) continue
            val comm = nameOf(tid) ?: continue
            val owned = comm == marker || (comm !in java && RENDERER.none { comm.startsWith(it) })
            if (!owned) continue
            try {
                setPriority(tid, Process.THREAD_PRIORITY_BACKGROUND)
            } catch (_: RuntimeException) {
                // exited since the listing, or not ours to change
            }
        }
    }
}
