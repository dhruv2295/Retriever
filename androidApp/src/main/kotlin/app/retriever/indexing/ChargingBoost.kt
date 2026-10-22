package app.retriever.indexing

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import java.io.File

/**
 * On a charger, processing runs at normal priority and, in [Mode.HINT], under a performance hint session. Hidden, Android
 * kept Retriever on the small cores (a Pixel 8 Pro trace: about 9 s of every 20 on each small core, none on the large one)
 * and photos took 1.5 s against 0.4 s in front. Off the charger the threads stay at background priority.
 */
class ChargingBoost(
    private val context: Context,
    private val tasks: () -> List<Int> = { File("/proc/self/task").list().orEmpty().mapNotNull { it.toIntOrNull() } },
) {
    enum class Mode { OFF, PRIORITY, HINT }

    // ponytail: the mode is a file the experiment writes with run-as; becomes a constant once one mode wins on devices.
    fun mode(): Mode = runCatching { Mode.valueOf(File(context.filesDir, MODE_FILE).readText().trim()) }.getOrDefault(Mode.OFF)

    private val raised = mutableSetOf<Int>()
    private var session: PerformanceHintManager.Session? = null
    private var lastReport = System.nanoTime()

    /** Called before each batch and every few seconds while one runs, so threads the model starts mid-batch are caught. */
    @Synchronized
    fun apply(pluggedIn: Boolean) {
        val mode = if (pluggedIn) mode() else Mode.OFF
        if (mode == Mode.OFF) {
            release()
            return
        }
        // The process's own threads at background priority: the index and read-ahead threads and the model runtime's pools.
        for (tid in tasks()) {
            if (tid in raised) continue
            val lowered = runCatching { Process.getThreadPriority(tid) == Process.THREAD_PRIORITY_BACKGROUND }.getOrDefault(false)
            if (lowered && runCatching { Process.setThreadPriority(tid, Process.THREAD_PRIORITY_DEFAULT) }.isSuccess) raised += tid
        }
        // Every thread, ML Kit's recognizers included (normal priority already, much of the work).
        if (mode == Mode.HINT && Build.VERSION.SDK_INT >= 31) hint(tasks().toIntArray())
    }

    /** One file done: how long it took, against the target. */
    @Synchronized
    fun fileDone() {
        val now = System.nanoTime()
        val took = now - lastReport
        lastReport = now
        if (Build.VERSION.SDK_INT >= 31) session?.reportActualWorkDuration(took)
    }

    @Synchronized
    fun release() {
        for (tid in raised) runCatching { Process.setThreadPriority(tid, Process.THREAD_PRIORITY_BACKGROUND) }
        raised.clear()
        if (Build.VERSION.SDK_INT >= 31) session?.close()
        session = null
    }

    private fun hint(tids: IntArray) {
        if (Build.VERSION.SDK_INT < 31 || tids.isEmpty()) return
        val current = session
        if (current == null) {
            val manager = context.getSystemService(PerformanceHintManager::class.java) ?: return
            session = runCatching { manager.createHintSession(tids, TARGET_NANOS) }.getOrNull()
            lastReport = System.nanoTime()
        } else if (Build.VERSION.SDK_INT >= 34) {
            runCatching { current.setThreads(tids) }
        }
    }

    companion object {
        const val MODE_FILE = "charging-boost"

        /** A photo's GPU embed, which read-ahead preparation should keep up with. */
        const val TARGET_NANOS = 500_000_000L
    }
}
