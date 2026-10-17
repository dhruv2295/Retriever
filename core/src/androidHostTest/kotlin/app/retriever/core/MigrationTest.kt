package app.retriever.core

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.retriever.core.db.IndexDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MigrationTest {
    @Test
    fun version1FilesGainPartialAndWhatTheOldCapsCutIsReadAgain() {
        assertEquals(3L, IndexDb.Schema.version)
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        createOldSchema(driver, version = 1)
        driver.execute(
            null,
            "INSERT INTO files (uri, name, folder, mime, kind, size, modified_at, state, attempts) VALUES " +
                "('content://a', 'a.pdf', 'Docs', 'application/pdf', 'PDF', 10, 1, 'INDEXED', 1), " +
                "('content://big', 'notes.txt', 'Docs', 'text/plain', 'DOC', 1000000, 1, 'INDEXED', 1), " +
                "('content://small', 'todo.txt', 'Docs', 'text/plain', 'DOC', 1000, 1, 'INDEXED', 1), " +
                "('content://photo', 'IMG_1.jpg', 'DCIM', 'image/jpeg', 'PHOTO', 5000000, 1, 'INDEXED', 1)",
            0,
        )

        IndexDb.Schema.migrate(driver, 1, IndexDb.Schema.version).value

        val store = Store(driver)
        val pdf = store.fileByUri("content://a")!!
        assertEquals("a.pdf", pdf.file.name)
        assertFalse(pdf.partial)
        // What the old caps could have cut short is read again; the rest stays as it was.
        assertEquals(FileState.PENDING, pdf.state)
        assertEquals(FileState.PENDING, store.fileByUri("content://big")!!.state)
        assertEquals(FileState.INDEXED, store.fileByUri("content://small")!!.state)
        assertEquals(FileState.INDEXED, store.fileByUri("content://photo")!!.state)
    }

    @Test
    fun version2KeepsEveryFileIndexedAndBecomesSearchableInAnyScript() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        createOldSchema(driver, version = 2)
        val bengali = "বিদ্যুৎ"
        driver.execute(
            null,
            "INSERT INTO files (id, uri, name, folder, mime, kind, size, modified_at, state, attempts, partial) VALUES " +
                "(1, 'content://bill', 'bill.pdf', 'Documents', 'application/pdf', 'PDF', 10, 5, 'INDEXED', 1, 0), " +
                "(2, 'content://photo', 'IMG_1.jpg', 'DCIM/Camera', 'image/jpeg', 'PHOTO', 20, 7, 'INDEXED', 1, 0), " +
                "(3, 'content://new', 'new.txt', 'Documents', 'text/plain', 'DOC', 30, 9, 'PENDING', 0, 0)",
            0,
        )
        val vector = Vector.of(FloatArray(DIMS) { 1f }, SIG)
        val chunks = listOf(Triple(1L, 1L, "$bengali বিল ₹1,200"), Triple(2L, 1L, "बिजली बिल due"), Triple(3L, 2L, null))
        for ((id, fileId, text) in chunks) {
            driver.execute(null, "INSERT INTO chunks (id, file_id, ordinal, locator, text, vector, scale) VALUES (?, ?, ?, '', ?, ?, ?)", 6) {
                bindLong(0, id)
                bindLong(1, fileId)
                bindLong(2, id)
                bindString(3, text)
                bindBytes(4, vector.bytes)
                bindDouble(5, vector.scale.toDouble())
            }
            if (text != null) driver.execute(null, "INSERT INTO chunk_fts (rowid, text) VALUES ($id, '$text')", 0)
        }
        driver.execute(null, "INSERT INTO file_fts (rowid, name, folder) SELECT id, name, folder FROM files", 0)
        driver.execute(null, "INSERT INTO index_meta (key, value) VALUES ('source_token', 'tok'), ('model_signature', '${SIG.value}')", 0)
        assertFalse(bengali in terms(driver), "version 2 split Bengali at its vowel signs")

        IndexDb.Schema.migrate(driver, 2, IndexDb.Schema.version).value

        val store = Store(driver)
        assertEquals(0L, count(driver, "SELECT COUNT(*) FROM chunk_fts"), "the upgrade leaves the refill to warmSearch")
        // A chunk the indexer writes before the refill is kept once, not doubled.
        driver.execute(null, "INSERT INTO chunk_fts (rowid, text) VALUES (2, 'बिजली बिल due')", 0)
        assertTrue(store.rebuildFtsIfPending())
        assertFalse(store.rebuildFtsIfPending(), "the refill runs once")
        assertNull(store.meta("source_token"), "one full sync fills taken_at")
        assertEquals(SIG, store.signature())
        assertEquals(mapOf(FileState.INDEXED to 2L, FileState.PENDING to 1L), store.countsByState(), "nothing is read again")
        assertTrue(bengali in terms(driver))
        assertEquals(listOf(1L), store.keywordSearch(bengali, 10).map { it.chunkId })
        assertEquals(listOf(2L), store.keywordSearch("बिजली", 10).map { it.chunkId })
        assertEquals(listOf(2L), store.fileKeywordSearch("camera", 10))
        assertEquals(2L, count(driver, "SELECT COUNT(*) FROM chunk_fts"))
        assertEquals(3L, count(driver, "SELECT COUNT(*) FROM file_fts"))
        assertEquals(3, store.loadVectors().size)
        assertNull(store.fileByUri("content://photo")!!.file.takenAt)

        // The next sync brings the capture time without queueing the photo again.
        store.upsertFiles(listOf(store.fileByUri("content://photo")!!.file.copy(takenAt = 3L)))
        assertEquals(3L, store.fileByUri("content://photo")!!.file.takenAt)
        assertEquals(FileState.INDEXED, store.fileByUri("content://photo")!!.state)
        store.setStarred("content://photo", true, at = 1)
        assertEquals(1L, store.starredCount())
    }

    private fun terms(driver: SqlDriver): List<String> {
        driver.execute(null, "CREATE VIRTUAL TABLE terms USING fts4aux(chunk_fts)", 0)
        return driver.executeQuery(null, "SELECT term FROM terms WHERE col = '*'", { c ->
            QueryResult.Value(buildList { while (c.next().value) add(c.getString(0)!!) })
        }, 0).value.also { driver.execute(null, "DROP TABLE terms", 0) }
    }

    private fun count(driver: SqlDriver, sql: String): Long =
        driver.executeQuery(null, sql, { c -> c.next(); QueryResult.Value(c.getLong(0)!!) }, 0).value

    /** The tables as schema [version] 1 or 2 created them; version 1 had no partial column. */
    private fun createOldSchema(driver: SqlDriver, version: Int) {
        // Version 2's tokenchars: the Devanagari signs only.
        val devanagari = ((0x900..0x903) + (0x93A..0x94F) + (0x951..0x957) + (0x962..0x963)).joinToString("") { it.toChar().toString() }
        val tokenizer = "tokenize=unicode61 \"remove_diacritics=2\" \"tokenchars=$devanagari\""
        listOf(
            """
            CREATE TABLE files (
              id INTEGER PRIMARY KEY, uri TEXT NOT NULL UNIQUE, name TEXT NOT NULL, folder TEXT NOT NULL,
              mime TEXT NOT NULL, kind TEXT NOT NULL, size INTEGER NOT NULL, modified_at INTEGER NOT NULL,
              state TEXT NOT NULL DEFAULT 'PENDING', attempts INTEGER NOT NULL DEFAULT 0
              ${if (version >= 2) ", partial INTEGER NOT NULL DEFAULT 0" else ""}
            )
            """,
            "CREATE INDEX files_by_state ON files(state)",
            """
            CREATE TABLE chunks (
              id INTEGER PRIMARY KEY AUTOINCREMENT, file_id INTEGER NOT NULL REFERENCES files(id) ON DELETE CASCADE,
              ordinal INTEGER NOT NULL, locator TEXT NOT NULL DEFAULT '', text TEXT, vector BLOB, scale REAL,
              tail_attempts INTEGER NOT NULL DEFAULT 0
            )
            """,
            "CREATE INDEX chunks_by_file ON chunks(file_id)",
            "CREATE INDEX chunks_tail ON chunks(file_id) WHERE vector IS NULL",
            "CREATE VIRTUAL TABLE chunk_fts USING fts4(text, $tokenizer)",
            "CREATE VIRTUAL TABLE file_fts USING fts4(name, folder, $tokenizer)",
            "CREATE TABLE index_meta (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
            "CREATE TABLE recent_queries (phrase TEXT NOT NULL PRIMARY KEY, at INTEGER NOT NULL)",
        ).forEach { driver.execute(null, it.trimIndent(), 0) }
    }
}
