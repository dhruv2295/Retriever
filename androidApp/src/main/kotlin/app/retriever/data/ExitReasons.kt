package app.retriever.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context

/** One crash, ANR or native crash the system recorded for this app. Nothing is stored by us: the OS keeps the history. */
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

    internal class Raw(val reason: Int, val timestamp: Long, val description: String?)

    internal fun problems(raw: List<Raw>): List<ExitProblem> = raw
        .mapNotNull { r -> labels[r.reason]?.let { ExitProblem(r.timestamp, it, r.description?.takeIf(String::isNotBlank) ?: "No details") } }
        .sortedByDescending { it.atMillis }

    /** What the share sheet carries: plain text the person can read before sending. */
    fun shareText(problems: List<ExitProblem>): String =
        problems.joinToString("\n") { "${java.util.Date(it.atMillis)}: ${it.kind}, ${it.description}" }
}
