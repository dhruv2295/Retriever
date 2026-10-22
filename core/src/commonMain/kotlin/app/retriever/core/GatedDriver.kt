package app.retriever.core

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlinx.coroutines.delay
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * Closes only between statements and transactions: closing the native driver under a running statement crashed in
 * SQLite. A call after [closeWhenIdle] began throws instead of reaching the closed database, except a statement of a
 * transaction already open, which finishes.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class GatedDriver(private val inner: SqlDriver) : SqlDriver by inner {
    private val running = AtomicInt(0)

    @Volatile
    private var closing = false

    @Volatile
    private var closed = false

    /**
     * Holds close off while [block] runs. Store runs each whole transaction in one, so its commit, hooks and listeners
     * are covered however they end. Counted before the check, so a call can't slip in between close's last look and
     * the close itself.
     */
    fun <T> counted(block: () -> T): T {
        running.incrementAndFetch()
        try {
            if (closed || (closing && inner.currentTransaction() == null)) throw IllegalStateException("The index database is closed")
            return block()
        } finally {
            running.decrementAndFetch()
        }
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> = counted { inner.executeQuery(identifier, sql, mapper, parameters, binders) }

    override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> =
        counted { inner.execute(identifier, sql, parameters, binders) }

    override fun newTransaction(): QueryResult<Transacter.Transaction> = counted { inner.newTransaction() }

    suspend fun closeWhenIdle() {
        closing = true
        while (running.load() > 0) delay(2)
        // Then once more: a call that entered just before [closed] may still be asking for its transaction.
        closed = true
        while (running.load() > 0) delay(2)
        inner.close()
    }
}
