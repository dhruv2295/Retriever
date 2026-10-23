package app.retriever.core

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.retriever.core.db.IndexDb
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A 100k-file library on the JVM: what a sync's diff holds and allocates, how many statements a first sync runs per
 * file, and how long the longest write transaction of a whole-library removal holds the writer.
 */
class SyncBenchTest {
    private val n = 100_000
    private var statements = 0L
    private val transactions = mutableListOf<Long>()
    private val raw = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { IndexDb.Schema.create(it) }
    private val spy = object : SpyDriver(raw, onQuery = { _, _ -> statements++ }, onExecute = { _, _ -> statements++ }) {
        override fun newTransaction(): QueryResult<Transacter.Transaction> {
            val outermost = raw.currentTransaction() == null
            val start = System.nanoTime()
            return raw.newTransaction().also { t -> if (outermost) t.value.afterCommit { transactions += System.nanoTime() - start } }
        }
    }
    private val store = Store(spy)
    private val source = FakeSource()
    private val sync = LibrarySync(store, source, now = { 0L })
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private fun file(i: Int) = SourceFile(
        "content://media/external_primary/file/${1_000_000 + i}",
        "IMG_20240518_${100_000 + i}.jpg",
        "DCIM/Camera ${i % 40}",
        "image/jpeg",
        Kind.PHOTO,
        2_000_000L + i,
        1_715_000_000_000L + i * 1_000L,
        1_715_000_000_000L + i,
    )

    private class Run(val ms: Double, val allocatedMb: Double)

    /** The fastest of [times] runs of [block], and what that run allocated. */
    private fun measure(times: Int = 1, block: () -> Unit): Run = (1..times).map {
        val bytes = threads.currentThreadAllocatedBytes
        val start = System.nanoTime()
        block()
        Run((System.nanoTime() - start) / 1e6, (threads.currentThreadAllocatedBytes - bytes) / 1_048_576.0)
    }.minBy { it.ms }

    private fun usedHeap(): Long {
        repeat(3) { System.gc() }
        return Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    }

    /** The live heap [build]'s map holds, in MB; its size is read after, so the map is still reachable. */
    private fun retainedMb(build: () -> Map<*, *>): Double {
        val before = usedHeap()
        val held = build()
        val after = usedHeap()
        assertEquals(n, held.size)
        return (after - before) / 1_048_576.0
    }

    @Test
    fun hundredThousandFiles() {
        val files = List(n) { file(it) }
        statements = 0
        source.next = Changes(files, emptyList(), "t1", isFullScan = true)
        val first = measure { runBlocking { sync.sync(forceFull = true) } }
        val perFile = statements.toDouble() / n

        source.next = Changes(files, emptyList(), "t2", isFullScan = true)
        val identical = measure(times = 3) { runBlocking { assertEquals(SyncResult(0, 0, 0, true), sync.sync(forceFull = true)) } }

        val present = files.mapTo(HashSet()) { it.uri }
        source.next = Changes(emptyList(), emptyList(), "t3", isFullScan = false, present = present, scope = setOf("content://media/"))
        val incremental = measure(times = 3) { runBlocking { assertEquals(SyncResult(0, 0, 0, false), sync.sync()) } }

        val knownMb = retainedMb { store.knownFiles() }
        val fingerprintsMb = retainedMb { store.knownFingerprints() }

        transactions.clear()
        source.next = Changes(emptyList(), emptyList(), "t4", isFullScan = false, present = emptySet(), scope = setOf("content://media/"))
        val removal = measure { runBlocking { assertEquals(SyncResult(0, 0, n, false), sync.sync()) } }
        val longest = transactions.maxOrNull()?.div(1e6) ?: 0.0

        println(
            "SyncBench %d files: first sync %.0f ms, %.2f statements per file, %.0f MB allocated; identical full scan %.0f ms, %.0f MB allocated; "
                .format(n, first.ms, perFile, first.allocatedMb, identical.ms, identical.allocatedMb) +
                "incremental with every id present %.0f ms, %.0f MB allocated; knownFiles holds %.1f MB, knownFingerprints %.1f MB; "
                    .format(incremental.ms, incremental.allocatedMb, knownMb, fingerprintsMb) +
                "removing all %.0f ms in %d transactions, longest %.0f ms".format(removal.ms, transactions.size, longest),
        )
    }
}
