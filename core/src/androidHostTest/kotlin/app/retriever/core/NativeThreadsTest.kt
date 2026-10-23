package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals

class NativeThreadsTest {
    private val background = 10 // android.os.Process.THREAD_PRIORITY_BACKGROUND

    private val names = mutableMapOf<Int, String>()
    private val renames = mutableListOf<String>()
    private var current = "caller"

    private fun rename(name: String): String = current.also {
        current = name
        renames += name
    }

    @Test
    fun lowersOnlyTheRuntimesThreadsAndToleratesExits() {
        val snapshots = ArrayDeque(listOf(listOf(1, 2), listOf(1, 2, 3, 4, 5, 6, 7, 8)))
        val lowered = mutableListOf<Pair<Int, Int>>()
        val result = lowerNewThreads(
            tasks = { snapshots.removeFirst() },
            nameOf = { tid -> names[tid] },
            javaNames = { setOf("DefaultDispatch", "binder:1_3") },
            rename = ::rename,
            setPriority = { tid, p -> if (tid == 4) throw IllegalArgumentException("gone") else lowered += tid to p },
        ) {
            val marker = current
            names += mapOf(3 to marker, 4 to marker, 5 to "mali-cmar-backe", 6 to "DefaultDispatch", 7 to "binder:1_3", 8 to "RenderThread")
            "ok"
        }
        assertEquals("ok", result)
        // 3: a pool started under the marker; 5: a native thread that named itself. 6-8 are shared and stay as they were.
        assertEquals(listOf(3 to background, 5 to background), lowered)
        assertEquals("caller", current, "the caller gets its name back")
    }

    @Test
    fun aPoolStartedFromASharedWorkerIsStillOwned() {
        current = "DefaultDispatch"
        val snapshots = ArrayDeque(listOf(listOf(1), listOf(1, 9)))
        val lowered = mutableListOf<Int>()
        lowerNewThreads({ snapshots.removeFirst() }, { names[it] }, { setOf("DefaultDispatch") }, ::rename, { tid, _ -> lowered += tid }) {
            names[9] = current
        }
        assertEquals(listOf(9), lowered)
    }

    @Test
    fun lowersThreadsStartedBeforeABlockFailure() {
        val snapshots = ArrayDeque(listOf(listOf(1), listOf(1, 9)))
        val lowered = mutableListOf<Int>()
        runCatching {
            lowerNewThreads({ snapshots.removeFirst() }, { names[it] }, { emptySet() }, ::rename, { tid, _ -> lowered += tid }) {
                names[9] = current
                error("open failed")
            }
        }
        assertEquals(listOf(9), lowered)
        assertEquals("caller", current)
    }
}
