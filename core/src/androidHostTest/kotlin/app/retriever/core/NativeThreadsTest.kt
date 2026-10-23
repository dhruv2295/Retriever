package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class NativeThreadsTest {
    private val background = 10 // android.os.Process.THREAD_PRIORITY_BACKGROUND

    @Test
    fun lowersOnlyTheThreadsTheBlockStartedAndToleratesExits() {
        val snapshots = ArrayDeque(listOf(listOf(1, 2), listOf(1, 2, 3, 4, 5, 6, 7, 8)))
        val names = mutableMapOf(
            1 to "main",
            2 to "Retriever-index",
            5 to "mali-cmar-backe",
            6 to "DefaultDispatch",
            7 to "binder:1_3",
            8 to "RenderThread",
        )
        val lowered = mutableListOf<Pair<Int, Int>>()
        val caller = Thread.currentThread().name
        val result = lowerNewThreads(
            tasks = { snapshots.removeFirst() },
            name = { names[it] },
            setPriority = { tid, p -> if (tid == 4) throw IllegalArgumentException("gone") else lowered += tid to p },
        ) {
            // Native threads take the name of the thread that starts them.
            val marker = Thread.currentThread().name
            assertNotEquals(caller, marker)
            names[3] = marker
            names[4] = marker
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(caller, Thread.currentThread().name, "the caller gets its name back")
        // 5 named itself (a GPU driver thread, maybe HWUI's); 6-8 are shared JVM, Binder and HWUI threads other code
        // started meanwhile: none is the runtime's.
        assertEquals(listOf(3 to background), lowered)
    }

    @Test
    fun aPoolStartedFromASharedWorkerIsStillOwned() {
        val thread = Thread.currentThread()
        val own = thread.name
        thread.name = "DefaultDispatch"
        try {
            val snapshots = ArrayDeque(listOf(listOf(1), listOf(1, 9)))
            val names = mutableMapOf(1 to "DefaultDispatch")
            val lowered = mutableListOf<Int>()
            lowerNewThreads({ snapshots.removeFirst() }, { names[it] }, { tid, _ -> lowered += tid }) {
                names[9] = Thread.currentThread().name
            }
            assertEquals(listOf(9), lowered)
            assertEquals("DefaultDispatch", thread.name)
        } finally {
            thread.name = own
        }
    }

    @Test
    fun lowersThreadsStartedBeforeABlockFailure() {
        val snapshots = ArrayDeque(listOf(listOf(1), listOf(1, 8, 9)))
        var marker = ""
        val lowered = mutableListOf<Int>()
        val caller = Thread.currentThread().name
        runCatching {
            lowerNewThreads({ snapshots.removeFirst() }, { if (it == 9) marker else "binder:42_3" }, { tid, _ -> lowered += tid }) {
                marker = Thread.currentThread().name
                error("open failed")
            }
        }
        assertEquals(listOf(9), lowered)
        assertEquals(caller, Thread.currentThread().name)
    }
}
