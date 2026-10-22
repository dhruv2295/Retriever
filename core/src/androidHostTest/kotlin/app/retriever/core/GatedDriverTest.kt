package app.retriever.core

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class GatedDriverTest {
    private val driver = GatedDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)).apply {
        execute(null, "CREATE TABLE t (x INTEGER)", 0)
    }

    /** As Store runs one: the whole transaction holds the gate. */
    private fun transaction(body: () -> Unit) = driver.counted { object : TransacterImpl(driver) {}.transaction { body() } }

    @Test
    fun closeLetsTheOpenTransactionFinishThenRefusesCalls() = runBlocking<Unit> {
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        var failure: Throwable? = null
        val writer = thread {
            try {
                transaction {
                    driver.execute(null, "INSERT INTO t VALUES (1)", 0)
                    inside.countDown()
                    release.await()
                    // After close began: the open transaction still finishes.
                    driver.execute(null, "INSERT INTO t VALUES (2)", 0)
                }
            } catch (e: Throwable) {
                failure = e
            }
        }
        inside.await()
        val closing = async(Dispatchers.Default) { driver.closeWhenIdle() }
        delay(200)
        assertFalse(closing.isCompleted, "closed under a running transaction")
        release.countDown()
        closing.await()
        writer.join()
        assertNull(failure)
        assertFailsWith<IllegalStateException> { driver.execute(null, "INSERT INTO t VALUES (3)", 0) }
    }

    @Test
    fun aTransactionWhoseCommitHookThrowsStillLetsCloseFinish() = runBlocking<Unit> {
        // SQLDelight skips the remaining post-commit hooks when one throws, so a count kept in a hook would leak.
        assertFailsWith<IllegalArgumentException> {
            driver.counted {
                object : TransacterImpl(driver) {}.transaction {
                    afterCommit { throw IllegalArgumentException("hook") }
                    driver.execute(null, "INSERT INTO t VALUES (1)", 0)
                }
            }
        }
        withTimeout(5_000) { driver.closeWhenIdle() }
    }
}
