package app.retriever

import android.app.ApplicationExitInfo
import app.retriever.data.ExitReasons
import app.retriever.data.ExitReasons.Raw
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
}
