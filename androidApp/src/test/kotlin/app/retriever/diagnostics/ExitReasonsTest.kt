package app.retriever.diagnostics

import android.app.ApplicationExitInfo
import app.retriever.diagnostics.ExitReasons.Raw
import org.junit.Assert.assertEquals
import org.junit.Test

class ExitReasonsTest {
    @Test fun crashAndAnrBecomeRowsAndUserExitsAreIgnored() {
        val rows = ExitReasons.problems(
            listOf(
                Raw(ApplicationExitInfo.REASON_USER_REQUESTED, 5, "swiped away"),
                Raw(ApplicationExitInfo.REASON_CRASH, 10, "java.lang.IllegalStateException"),
                Raw(ApplicationExitInfo.REASON_ANR, 30, null),
                Raw(ApplicationExitInfo.REASON_EXIT_SELF, 40, "bye"),
            ),
        )
        assertEquals(listOf("Not responding" to "No details", "Crash" to "java.lang.IllegalStateException"), rows.map { it.kind to it.description })
        assertEquals(listOf(30L, 10L), rows.map { it.atMillis })
    }

    @Test fun onlyNativeCrashesAndSignalsCountAsAModelCrash() {
        val crashes = listOf(
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_ANR,
            null,
        ).map { ExitReasons.crashed(it, null) }
        assertEquals(listOf(true, true, false, false, false, false, false), crashes)
    }

    /** Android 17's per-app memory limit repeats on every open, so it counts as the model's doing; other REASON_OTHER exits don't. */
    @Test fun aMemoryLimiterKillCountsAsAModelCrashAndIsListed() {
        val limited = "MemoryLimiter:AnonSwap rss=2.1GB"
        assertEquals(true, ExitReasons.crashed(ApplicationExitInfo.REASON_OTHER, limited))
        assertEquals(false, ExitReasons.crashed(ApplicationExitInfo.REASON_OTHER, "user request"))
        assertEquals(false, ExitReasons.crashed(ApplicationExitInfo.REASON_OTHER, null))

        val rows = ExitReasons.problems(
            listOf(Raw(ApplicationExitInfo.REASON_OTHER, 20, limited), Raw(ApplicationExitInfo.REASON_OTHER, 30, "user request")),
        )
        assertEquals(listOf("Memory limit" to limited), rows.map { it.kind to it.description })
    }
}
