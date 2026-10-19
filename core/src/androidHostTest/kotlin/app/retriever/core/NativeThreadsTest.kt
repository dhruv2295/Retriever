package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals

class NativeThreadsTest {
    private val background = 10 // android.os.Process.THREAD_PRIORITY_BACKGROUND

    @Test
    fun lowersOnlyThreadsStartedByTheBlockAndToleratesExits() {
        val snapshots = ArrayDeque(listOf(listOf(1, 2), listOf(1, 2, 3, 4, 5)))
        val lowered = mutableListOf<Pair<Int, Int>>()
        val result = lowerNewThreads(
            tasks = { snapshots.removeFirst() },
            setPriority = { tid, p -> if (tid == 4) throw IllegalArgumentException("gone") else lowered += tid to p },
        ) { "ok" }
        assertEquals("ok", result)
        assertEquals(listOf(3 to background, 5 to background), lowered)
    }

    @Test
    fun lowersThreadsStartedBeforeABlockFailure() {
        val snapshots = ArrayDeque(listOf(listOf(1), listOf(1, 9)))
        val lowered = mutableListOf<Int>()
        runCatching { lowerNewThreads({ snapshots.removeFirst() }, { tid, _ -> lowered += tid }) { error("open failed") } }
        assertEquals(listOf(9), lowered)
    }
}
