package app.retriever.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context

/** One crash, ANR, native crash or memory-limit kill the system recorded for this app. Nothing is stored by us: the OS keeps the history. */
data class ExitProblem(val atMillis: Long, val kind: String, val description: String)

object ExitReasons {
    private val labels = mapOf(
        ApplicationExitInfo.REASON_CRASH to "Crash",
        ApplicationExitInfo.REASON_CRASH_NATIVE to "Native crash",
        ApplicationExitInfo.REASON_ANR to "Not responding",
    )

    /** Newest first. Exits the person caused (swiped away, force-stopped) and routine ones are left out. */
    fun read(context: Context): List<ExitProblem> {
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return problems(am.getHistoricalProcessExitReasons(null, 0, 0).map { Raw(it.reason, it.timestamp, it.description) })
    }

    /** The previous process died of a native crash or a signal: what a model open killing the process looks like. */
    fun lastExitCrashed(context: Context): Boolean =
        crashed(context.getSystemService(ActivityManager::class.java)?.getHistoricalProcessExitReasons(null, 0, 1)?.firstOrNull()?.reason)

    /**
     * A Java crash, the low-memory killer and the person's own swipe are not the model's doing. Nor is Android 17's per-app
     * memory limit (REASON_OTHER, "MemoryLimiter"): what else is resident shares the blame, and counting it would demote the
     * GPU or the chip's build for good (ModelHost.checkUnfinishedOpens). It is still listed, as "Memory limit".
     */
    internal fun crashed(reason: Int?): Boolean = reason == ApplicationExitInfo.REASON_CRASH_NATIVE || reason == ApplicationExitInfo.REASON_SIGNALED

    private fun memoryLimited(reason: Int?, description: String?) = reason == ApplicationExitInfo.REASON_OTHER && description?.contains(MEMORY_LIMITER) == true

    private const val MEMORY_LIMITER = "MemoryLimiter"

    internal class Raw(val reason: Int, val timestamp: Long, val description: String?)

    internal fun problems(raw: List<Raw>): List<ExitProblem> = raw
        .mapNotNull { r ->
            val kind = if (memoryLimited(r.reason, r.description)) "Memory limit" else labels[r.reason]
            kind?.let { ExitProblem(r.timestamp, it, r.description?.takeIf(String::isNotBlank) ?: "No details") }
        }
        .sortedByDescending { it.atMillis }

    /** What the share sheet carries: plain text the person can read before sending. */
    fun shareText(problems: List<ExitProblem>): String = problems.joinToString("\n") { "${java.util.Date(it.atMillis)}: ${it.kind}, ${it.description}" }
}
