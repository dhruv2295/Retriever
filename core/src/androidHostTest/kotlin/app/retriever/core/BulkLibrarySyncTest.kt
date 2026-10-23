package app.retriever.core

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.retriever.core.db.IndexDb
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The sync writes only what changed and reads the store in bulk. */
class BulkLibrarySyncTest {
    private val sql = mutableListOf<String>()
    private val raw = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { IndexDb.Schema.create(it) }
    private val spy = object : SqlDriver by raw {
        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> = raw.executeQuery(identifier, sql, mapper, parameters, binders).also { this@BulkLibrarySyncTest.sql += sql }
    }
    private val store = Store(spy).also { it.resetForModel(SIG) }
    private val source = FakeSource()
    private val sync = LibrarySync(store, source, now = { 0L })

    private fun totalChanges(): Long = raw.executeQuery(null, "SELECT total_changes()", { c ->
        c.next()
        QueryResult.Value(c.getLong(0)!!)
    }, 0).value

    private fun full(files: List<SourceFile>) = runBlocking {
        source.next = Changes(files, emptyList(), "tok", isFullScan = true)
        sync.sync(forceFull = true)
    }

    private fun incremental(files: List<SourceFile>) = runBlocking {
        source.next = Changes(files, emptyList(), "tok2", isFullScan = false)
        sync.sync()
    }

    @Test
    fun kindOnlyChangeUpdatesNames() = runBlocking {
        val photo = testFile("beach.jpg", Kind.PHOTO)
        full(listOf(photo, testFile("notes.txt")))
        sync.loadNames()
        val result = incremental(listOf(photo.copy(kind = Kind.SCREENSHOT)))
        assertEquals(SyncResult(0, 1, 0, false), result)
        val fresh = NameIndex.build(store.nameEntries())
        for (k in listOf(Kind.SCREENSHOT, Kind.PHOTO)) {
            assertEquals(fresh.search("beach", setOf(k)), sync.names!!.search("beach", setOf(k)))
        }
        assertEquals(1, sync.names!!.search("beach", setOf(Kind.SCREENSHOT)).size)
    }

    @Test
    fun identicalFullScanWritesNothing() {
        val files = (1..5).map { testFile("f$it.txt", takenAt = it.toLong()) }
        full(files)
        val before = totalChanges()
        assertEquals(SyncResult(0, 0, 0, true), full(files))
        assertEquals(before, totalChanges())
    }

    @Test
    fun incrementalSyncLooksUpInBulk() {
        val files = (1..10).map { testFile("f$it.txt") }
        full(files)
        sql.clear()
        val result = incremental(files.take(3).map { it.copy(modifiedAt = 2L) })
        assertEquals(SyncResult(0, 3, 0, false), result)
        assertEquals(1, sql.count { "files.uri, files.name" in it && "FROM files WHERE uri IN" in it }, "bulk lookups: $sql")
        assertTrue(sql.none { it.trimEnd().endsWith("FROM files") }, "full read: $sql")
        assertTrue(sql.none { "files.uri, files.name" in it && "FROM files WHERE uri = ?" in it }, "point query: $sql")
    }
}
