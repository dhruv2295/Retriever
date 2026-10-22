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
 * SQLite. A call after [closeWhenIdle] began throws instead of reaching the closed database.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class GatedDriver(private val inner: SqlDriver) : SqlDriver by inner {
    private val running = AtomicInt(0)

    @Volatile
    private var closed = false

    // Counted before the check, so a call can't slip in between close's last look and the close itself.
    private fun enter() {
        running.incrementAndFetch()
        if (closed) {
            running.decrementAndFetch()
            error("The index database is closed")
        }
    }

    private inline fun <T> counted(block: () -> T): T {
        enter()
        try {
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

    /** A transaction counts until it commits or rolls back: SQLDelight runs these hooks after it ends. */
    override fun newTransaction(): QueryResult<Transacter.Transaction> {
        enter()
        val transaction = try {
            inner.newTransaction()
        } catch (e: Throwable) {
            running.decrementAndFetch()
            throw e
        }
        // Registered first, so a later hook that throws can't skip them.
        transaction.value.afterCommit { running.decrementAndFetch() }
        transaction.value.afterRollback { running.decrementAndFetch() }
        return transaction
    }

    /** ponytail: polls; a transaction whose end throws keeps its count and this waits for good. */
    suspend fun closeWhenIdle() {
        closed = true
        while (running.load() > 0) delay(2)
        inner.close()
    }
}
