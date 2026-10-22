package app.retriever.core

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class GatedDriverTest {
    @Test
    fun closeWaitsForTheRunningTransactionThenRefusesCalls() = runBlocking<Unit> {
        val driver = GatedDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        driver.execute(null, "CREATE TABLE t (x INTEGER)", 0)
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer = thread {
            object : TransacterImpl(driver) {}.transaction {
                driver.execute(null, "INSERT INTO t VALUES (1)", 0)
                inside.countDown()
                release.await()
            }
        }
        inside.await()
        val closing = async(Dispatchers.Default) { driver.closeWhenIdle() }
        delay(200)
        assertFalse(closing.isCompleted, "closed under a running transaction")
        release.countDown()
        closing.await()
        writer.join()
        assertFailsWith<IllegalStateException> { driver.execute(null, "INSERT INTO t VALUES (2)", 0) }
    }
}
