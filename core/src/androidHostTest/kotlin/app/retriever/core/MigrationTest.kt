package app.retriever.core

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.retriever.core.db.IndexDb
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MigrationTest {
    @Test
    fun version1FilesGainPartialAndWhatTheOldCapsCutIsReadAgain() {
        assertEquals(7L, IndexDb.Schema.version)
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

    @Test
    fun version3GainsTheListIndexesAndTheQueriesUseThem() {
        // 3.db is SQLDelight's snapshot of schema 3 (verifyCommonMainIndexDbMigration replays 3.sqm on it too).
        val v3 = File.createTempFile("index-v3", ".db").apply { deleteOnExit() }
        File("src/commonMain/sqldelight/databases/3.db").copyTo(v3, overwrite = true)
        val migrated = JdbcSqliteDriver("jdbc:sqlite:${v3.path}")
        assertTrue("files_by_size" !in indexes(migrated))
        IndexDb.Schema.migrate(migrated, 3, IndexDb.Schema.version).value
        val fresh = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { IndexDb.Schema.create(it) }

        for (driver in listOf(migrated, fresh)) {
            assertTrue(indexes(driver).containsAll(listOf("files_by_modified", "files_by_size", "files_by_kind_time", "chunks_tail")))
            val tail = count(driver, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'chunks_tail' AND sql LIKE '%tail_attempts%WHERE vector IS NULL'")
            assertEquals(1L, tail, "chunks_tail covers tail_attempts and keeps its predicate")
            val relink = plan(
                driver,
                "UPDATE OR IGNORE starred SET uri = COALESCE((SELECT f.uri FROM files f WHERE f.name = starred.name AND " +
                    "f.size = starred.size AND f.modified_at = starred.modified_at AND f.uri NOT IN (SELECT uri FROM starred) " +
                    "ORDER BY f.id LIMIT 1), uri) WHERE uri NOT IN (SELECT uri FROM files)",
            )
            assertTrue(relink.any { "SEARCH f USING INDEX files_by_size" in it }, relink.toString())
            val newest = plan(
                driver,
                "SELECT * FROM files WHERE (?1 IS NULL OR kind = ?1) AND (?2 IS NULL OR folder = ?2) " +
                    "ORDER BY modified_at DESC, id LIMIT 10 OFFSET 0",
            )
            assertTrue(newest.any { "USING INDEX files_by_modified" in it } && newest.none { "TEMP B-TREE" in it }, newest.toString())
            val ofKind = plan(driver, "SELECT * FROM files WHERE kind IN ('PHOTO') ORDER BY COALESCE(taken_at, modified_at) DESC, id LIMIT 15")
            assertTrue(ofKind.any { "files_by_kind_time" in it }, ofKind.toString())
        }
        migrated.close()
    }

    @Test
    fun version5KeepsExtractedContentAndVectorsThenRebuildsMeaningWithoutRequeueingFiles() {
        val v5 = File.createTempFile("index-v5", ".db").apply { deleteOnExit() }
        File("src/commonMain/sqldelight/databases/3.db").copyTo(v5, overwrite = true)
        JdbcSqliteDriver("jdbc:sqlite:${v5.path}").use { driver ->
            IndexDb.Schema.migrate(driver, 3, 5).value
            driver.execute(
                null,
                "INSERT INTO files (id, uri, name, folder, mime, kind, size, modified_at, state) " +
                    "VALUES (1, 'photo', 'photo.jpg', '', 'image/jpeg', 'PHOTO', 10, 1, 'INDEXED')",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO chunks (id, file_id, ordinal, text, content_text, vector, scale) " +
                    "VALUES (1, 1, 1, 'receipt 48213', 'receipt 48213', X'01', 1.0), (2, 1, 2, NULL, NULL, X'01', 1.0)",
                0,
            )
            driver.execute(null, "INSERT INTO chunk_fts (rowid, text) VALUES (1, 'receipt 48213')", 0)
            IndexDb.Schema.migrate(driver, 5, IndexDb.Schema.version).value
            val store = Store(driver)
            assertEquals(2, store.vectorCount(), "a schema upgrade does not force a model download")
            assertEquals(1, store.keywordSearch("48213", 10).size)
            assertEquals("receipt 48213", store.chunkTexts(1).single().text)
            assertEquals(1L, count(driver, "SELECT visual FROM chunks WHERE id = 2"))
            store.resetForModel(Signature("new-model"))
            assertEquals(0, store.vectorCount())
            assertEquals(2L, store.chunkCount())
            assertEquals(FileState.INDEXED, store.storedById(1)?.state)
            assertEquals(1, store.keywordSearch("48213", 10).size)
            assertEquals(1L, store.countsNow().tailFiles)
            store.replaceChunks(1, listOf(Store.NewChunk(1, "", null, null, null, "beach", true)))
            assertEquals(1, store.keywordSearch("beach", 10).size)
            assertTrue(store.chunkTexts(1).isEmpty())
        }
    }

    /** Version 7 keeps each file's reach; the upgrade fills it from the chunks already read, so the counts don't move. */
    @Test
    fun version6FilesGainTheirReachFromTheChunksTheyHold() {
        val v6 = File.createTempFile("index-v6", ".db").apply { deleteOnExit() }
        File("src/commonMain/sqldelight/databases/3.db").copyTo(v6, overwrite = true)
        JdbcSqliteDriver("jdbc:sqlite:${v6.path}").use { driver ->
            IndexDb.Schema.migrate(driver, 3, 6).value
            driver.execute(
                null,
                "INSERT INTO files (id, uri, name, folder, mime, kind, size, modified_at, state) VALUES " +
                    "(1, 'a', 'a.txt', '', 'text/plain', 'DOC', 10, 1, 'INDEXED'), (2, 'b', 'b.txt', '', 'text/plain', 'DOC', 10, 1, 'INDEXED'), " +
                    "(3, 'c', 'c.txt', '', 'text/plain', 'DOC', 10, 1, 'PENDING')",
                0,
            )
            // a.txt: a head with a vector and a tail waiting for one; b.txt: its name only.
            driver.execute(
                null,
                "INSERT INTO chunks (file_id, ordinal, text, content_text, vector, scale) VALUES " +
                    "(1, 1, 'head words', 'head words', X'01', 1.0), (1, 2, 'tail words', 'tail words', NULL, NULL), (2, 0, NULL, NULL, X'01', 1.0)",
                0,
            )
            IndexDb.Schema.migrate(driver, 6, IndexDb.Schema.version).value

            assertTrue("files_by_reach" in indexes(driver) && "files_by_kind_state" !in indexes(driver), "the counts' index replaces its prefix")
            val store = Store(driver)
            assertTrue(store.storedById(1)!!.contentRead)
            assertFalse(store.storedById(2)!!.contentRead)
            val counts = store.countsNow(kinds = setOf(Kind.DOC))
            assertEquals(
                ReadingProgress(partlyRead = 1, scheduled = 1, nameOnly = 1, laterPasses = 1, counted = false),
                counts.progress,
                "no scan in this database",
            )
            assertEquals(MeaningCounts(partlyAdded = 1, added = 1), counts.meaning)
        }
    }

    @Test
    fun aFreshVersion5DatabaseHasItsOwnColumnOrderAndKeepsItsTextWhenMeaningIsAdded() {
        val v5 = File.createTempFile("fresh-v5", ".db").apply { deleteOnExit() }
        File("src/commonMain/sqldelight/databases/3.db").copyTo(v5, overwrite = true)
        JdbcSqliteDriver("jdbc:sqlite:${v5.path}").use { driver ->
            IndexDb.Schema.migrate(driver, 3, 5).value
            // The v5 fresh schema placed content_text in the middle; ALTER TABLE had appended it on upgrades.
            driver.execute(null, "DROP TABLE chunks", 0)
            driver.execute(
                null,
                "CREATE TABLE chunks (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "file_id INTEGER NOT NULL REFERENCES files(id) ON DELETE CASCADE, ordinal INTEGER NOT NULL, " +
                    "locator TEXT NOT NULL DEFAULT '', text TEXT, content_text TEXT, vector BLOB, scale REAL, " +
                    "tail_attempts INTEGER NOT NULL DEFAULT 0)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO files (id, uri, name, folder, mime, kind, size, modified_at, state) " +
                    "VALUES (1, 'doc', 'doc.txt', '', 'text/plain', 'DOC', 10, 1, 'INDEXED')",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO chunks (id, file_id, ordinal, text, content_text) " +
                    "VALUES (1, 1, 1, 'receipt 48213', 'receipt 48213')",
                0,
            )
            driver.execute(null, "INSERT INTO chunk_fts (rowid, text) VALUES (1, 'receipt 48213')", 0)
            IndexDb.Schema.migrate(driver, 5, IndexDb.Schema.version).value
            val store = Store(driver)
            val signature = Signature("new")
            store.resetForModel(signature)
            store.setChunkVector(1, Vector.of(FloatArray(DIMS) { 1f }, signature))
            assertEquals(1, store.vectorCount())
            assertEquals("receipt 48213", store.chunkTexts(1).single().text)
            assertEquals(1, store.keywordSearch("48213", 10).size)
        }
    }

    private fun indexes(driver: SqlDriver): List<String> = strings(driver, "SELECT name FROM sqlite_master WHERE type = 'index'", 0)

    private fun plan(driver: SqlDriver, sql: String): List<String> = strings(driver, "EXPLAIN QUERY PLAN $sql", 3)

    private fun strings(driver: SqlDriver, sql: String, column: Int): List<String> = driver.executeQuery(null, sql, { c ->
        QueryResult.Value(buildList { while (c.next().value) add(c.getString(column)!!) })
    }, 0).value

    private fun terms(driver: SqlDriver): List<String> {
        driver.execute(null, "CREATE VIRTUAL TABLE terms USING fts4aux(chunk_fts)", 0)
        return driver.executeQuery(null, "SELECT term FROM terms WHERE col = '*'", { c ->
            QueryResult.Value(buildList { while (c.next().value) add(c.getString(0)!!) })
        }, 0).value.also { driver.execute(null, "DROP TABLE terms", 0) }
    }

    private fun count(driver: SqlDriver, sql: String): Long = driver.executeQuery(null, sql, { c ->
        c.next()
        QueryResult.Value(c.getLong(0)!!)
    }, 0).value

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
