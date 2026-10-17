package app.retriever.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.retriever.core.db.IndexDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MigrationTest {
    @Test
    fun version1FilesGainPartialAndWhatTheOldCapsCutIsReadAgain() {
        assertEquals(2L, IndexDb.Schema.version)
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // The files table as schema version 1 created it.
        driver.execute(
            null,
            """
            CREATE TABLE files (
              id INTEGER PRIMARY KEY,
              uri TEXT NOT NULL UNIQUE,
              name TEXT NOT NULL,
              folder TEXT NOT NULL,
              mime TEXT NOT NULL,
              kind TEXT NOT NULL,
              size INTEGER NOT NULL,
              modified_at INTEGER NOT NULL,
              state TEXT NOT NULL DEFAULT 'PENDING',
              attempts INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            "INSERT INTO files (uri, name, folder, mime, kind, size, modified_at, state, attempts) VALUES " +
                "('content://a', 'a.pdf', 'Docs', 'application/pdf', 'PDF', 10, 1, 'INDEXED', 1), " +
                "('content://big', 'notes.txt', 'Docs', 'text/plain', 'DOC', 1000000, 1, 'INDEXED', 1), " +
                "('content://small', 'todo.txt', 'Docs', 'text/plain', 'DOC', 1000, 1, 'INDEXED', 1), " +
                "('content://photo', 'IMG_1.jpg', 'DCIM', 'image/jpeg', 'PHOTO', 5000000, 1, 'INDEXED', 1)",
            0,
        )

        IndexDb.Schema.migrate(driver, 1, 2).value

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
}
