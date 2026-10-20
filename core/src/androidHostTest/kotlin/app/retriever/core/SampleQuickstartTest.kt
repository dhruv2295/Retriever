package app.retriever.core

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.retriever.core.db.IndexDb
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The sample starts reading as soon as its plan exists, and reading a file costs the screens one shared count. */
class SampleQuickstartTest {
    private val upserts = AtomicInteger()
    private val countScans = AtomicInteger()

    @Volatile
    private var upsertsAtPlan: Int? = null

    private val raw = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { IndexDb.Schema.create(it) }
    private val spy = object : SqlDriver by raw {
        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            if ("GROUP BY kind, state, partial, reach" in sql && "WHERE id IN" !in sql) countScans.incrementAndGet()
            return raw.executeQuery(identifier, sql, mapper, parameters, binders)
        }

        override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
            if (sql.startsWith("INSERT INTO files ")) upserts.incrementAndGet()
            if ("index_meta" in sql && binders != null && Keys().apply(binders).strings.firstOrNull() == TASTE_IDS_KEY) {
                if (upsertsAtPlan == null) upsertsAtPlan = upserts.get()
            }
            return raw.execute(identifier, sql, parameters, binders)
        }
    }
    private val store = Store(spy).also { it.resetForModel(SIG) }

    private class Keys : SqlPreparedStatement {
        val strings = mutableListOf<String?>()
        override fun bindBytes(index: Int, bytes: ByteArray?) = Unit
        override fun bindLong(index: Int, long: Long?) = Unit
        override fun bindDouble(index: Int, double: Double?) = Unit
        override fun bindString(index: Int, string: String?) {
            strings += string
        }
        override fun bindBoolean(index: Int, boolean: Boolean?) = Unit
    }

    private val library = (1..5_000).map { testFile("f$it.txt", if (it % 5 == 0) Kind.PHOTO else Kind.DOC, modifiedAt = it * 60_000L) }

    @Test
    fun thePlansFilesAreWrittenBeforeTheRestOfTheLibrary() = runBlocking {
        val r = testRetriever(store, library, FakeExtractor())
        val plan = r.planTaste()
        assertEquals(plan.ids.size, upsertsAtPlan, "only the plan's rows exist when the plan is set")
        assertEquals(library.size.toLong(), store.fileCount(), "then the rest, before planTaste returns")
        assertEquals("tok", store.meta("source_token"), "the token is written last, once every row is in")
    }

    @Test
    fun aBatchsSyncSkipsWhileAnotherSyncRuns(): Unit = runBlocking {
        val source = FakeSource().apply { next = Changes(library, emptyList(), "tok", isFullScan = true) }
        val sync = LibrarySync(store, source) { 0L }
        val inFirst = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val running = launch {
            sync.sync(
                first = {
                    inFirst.complete(Unit)
                    release.await()
                },
            )
        }
        inFirst.await()
        assertNull(sync.syncUnlessRunning(), "the running sync covers the batch, which reads without waiting for it")
        assertEquals(1, source.calls.size, "and scans nothing again")
        release.complete(Unit)
        running.join()
        assertNotNull(sync.syncUnlessRunning())
    }

    /**
     * Five screens and three file reads of two writes each: each screen counting on its own is at least 5 × (1 + 2 × 3);
     * shared, it is one count per screen joining, the shared one's first, and at most one per write.
     */
    @Test
    fun everyScreenSharesOneCountPerWrite() = runBlocking {
        store.upsertFiles(library.take(20))
        val r = testRetriever(store, emptyList(), FakeExtractor()).apply { refreshSetUp() }
        val screens = (1..5).map { Channel<Store.Counts>(Channel.UNLIMITED) }
        val jobs = screens.map { ch -> launch(Dispatchers.Default) { r.counts().collect { ch.send(it) } } }
        suspend fun allSee(read: Long) = withTimeout(10.seconds) {
            screens.forEach { ch ->
                do {
                    val seen = ch.receive().progress.read
                } while (seen != read)
            }
        }
        allSee(0)
        val ids = store.pending(3).map { it.id }
        ids.forEachIndexed { i, id ->
            store.recordAttempt(id, 1)
            store.replaceChunks(id, listOf(Store.NewChunk(1, "", "words", null)))
            allSee(i + 1L)
        }
        jobs.forEach { it.cancel() }
        assertTrue(countScans.get() <= screens.size + 1 + 2 * ids.size, "${countScans.get()} library counts")
    }

    @Test
    fun theSampleTrimsToItsBudgetInWallTime() {
        val pending = (1..10).map { Store.StoredFile(it.toLong(), testFile("d$it.txt"), 0) }
        val costs = pending.associate { it.id to 3_000.0 } // 30 s of inference left
        assertEquals(emptySet(), tasteDrops(pending, costs, 90_000.0, 0.0, null, keepOne = false), "seeded: 30 s fits 90 s")
        assertEquals(emptySet(), tasteDrops(pending, costs, 90_000.0, 30_000.0, 1.0, keepOne = false), "30 s left of 60")
        assertEquals(4, tasteDrops(pending, costs, 90_000.0, 30_000.0, 3.0, keepOne = false).size, "90 s of wall time left of 60: keep 18 s of inference")
        assertEquals(9, tasteDrops(pending, costs, 90_000.0, 100_000.0, 3.0, keepOne = true).size, "past the budget, one file is still a trial")
    }
}
