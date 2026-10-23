@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.retriever.core

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The index database as the iOS app opens it, on the system SQLite. */
class IosStoreTest {
    private val dir = NSTemporaryDirectory() + "retriever-store-" + NSUUID().UUIDString

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(dir, null)
        NSFileManager.defaultManager.removeItemAtPath("$dir-user-state.json", null)
    }

    /** An older build opening a newer build's index makes the index again; settings and stars come back from their file. */
    @Test
    fun aNewerIndexIsMadeAgainKeepingSettingsAndStars() = runBlocking {
        val first = iosStore(dir) { _, _ -> }
        val file = testFile("a.pdf", Kind.PDF)
        first.upsertFiles(listOf(file))
        first.setStarred(file.uri, true, at = 1)
        first.updatePrefs { it.copy(excludedFolders = setOf("Private")) }
        first.close()
        iosIndexDriver(dir).let { driver ->
            driver.execute(null, "CREATE TABLE future (x INTEGER)", 0)
            driver.execute(null, "PRAGMA user_version = 99", 0)
            driver.close()
        }

        val again = iosStore(dir) { _, _ -> }
        assertEquals(setOf("Private"), again.prefs().excludedFolders)
        assertEquals(0L, again.fileCount())
        again.upsertFiles(listOf(file))
        assertEquals(setOf(file.uri), again.starredUris())
        again.close()
        iosIndexDriver(dir).let { driver ->
            assertEquals(0L, count(driver, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'future'"))
            driver.close()
        }
    }

    private fun count(driver: SqlDriver, sql: String): Long = driver.executeQuery(null, sql, { c ->
        c.next()
        QueryResult.Value(c.getLong(0)!!)
    }, 0).value
}

private fun testFile(name: String, kind: Kind = Kind.DOC, folder: String = "Documents") =
    SourceFile("folder://$folder/$name", name, folder, "text/plain", kind, 100L, 1L)
