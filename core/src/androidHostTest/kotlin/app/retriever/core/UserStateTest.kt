package app.retriever.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.retriever.core.db.IndexDb
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserStateTest {
    private val dir = Files.createTempDirectory("user-state").toFile()
    private val db = File(dir, Store.DB_NAME)
    private val snapshot = File(dir, "user-state.json")
    private val lines = mutableListOf<String>()

    /** As the platforms do it: a temporary file renamed over the old one. */
    private val file = UserStateFile(
        read = { snapshot.takeIf { it.exists() }?.readText() },
        write = { text ->
            val tmp = File(dir, "user-state.json.tmp").apply { writeText(text) }
            Files.move(tmp.toPath(), snapshot.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        },
    )

    private val drivers = mutableListOf<JdbcSqliteDriver>()

    private fun open(): Store {
        val fresh = !db.exists()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${db.path}").also { drivers += it }
        if (fresh) IndexDb.Schema.create(driver)
        return Store(driver, { m, t -> lines += m + (t?.let { " $it" }.orEmpty()) }, userState = file)
    }

    private fun resetIndex() {
        drivers.forEach { it.close() }
        drivers.clear()
        assertTrue(db.delete())
    }

    @AfterTest
    fun cleanUp() {
        drivers.forEach { it.close() }
        dir.deleteRecursively()
    }

    private val report = testFile("Tax Return 2025.pdf", Kind.PDF, modifiedAt = 50, folder = "Documents", size = 1234)

    @Test
    fun aDeletedIndexKeepsStarsAndSettings() = runTest {
        val store = open()
        store.upsertFiles(listOf(report))
        store.setStarred(report.uri, true, at = 7)
        store.updatePrefs { it.copy(onlyWhileCharging = true, kinds = setOf(Kind.PDF), excludedFolders = setOf("Private")) }

        resetIndex()
        val again = open()

        assertEquals(Prefs(onlyWhileCharging = true, kinds = setOf(Kind.PDF), excludedFolders = setOf("Private")), again.prefs())
        LibrarySync(again, FakeSource().apply { next = Changes(listOf(report), emptyList(), "t", isFullScan = true) }) { 0L }.sync()
        assertEquals(setOf(report.uri), again.starredUris())
    }

    @Test
    fun aStarFindsItsFileUnderANewUriButNotAFileOfAnotherSize() = runTest {
        val store = open()
        val other = testFile("scan.jpg", Kind.PHOTO, modifiedAt = 9, size = 500)
        store.upsertFiles(listOf(report, other))
        store.setStarred(report.uri, true, at = 1)
        store.setStarred(other.uri, true, at = 2)

        // A new phone: MediaStore ids differ, and one file was edited on the way.
        resetIndex()
        val moved = report.copy(uri = "content://media/external/file/991")
        val edited = other.copy(uri = "content://media/external/file/992", size = 501)
        val again = open()
        LibrarySync(again, FakeSource().apply { next = Changes(listOf(moved, edited), emptyList(), "t", isFullScan = true) }) { 0L }.sync()

        assertEquals(setOf(moved.uri), again.starredUris())
        // The relink was saved, so the next reset keeps the new uri.
        assertTrue(moved.uri in snapshot.readText())
    }

    @Test
    fun aStarAddedBeforeTheFirstReadDoesNotOverwriteTheSnapshot() {
        val store = open()
        store.upsertFiles(listOf(report))
        store.setStarred(report.uri, true, at = 1)

        resetIndex()
        val again = open()
        val fresh = testFile("new.txt")
        again.upsertFiles(listOf(report, fresh))
        again.setStarred(fresh.uri, true, at = 2)

        assertEquals(setOf(report.uri, fresh.uri), again.starredUris())
    }

    @Test
    fun aCorruptSnapshotIsIgnoredAndLogsNoName() {
        snapshot.writeText("""{"starred":[{"uri":"content://x","name":"Tax Return 2025.pdf","size":""")

        val store = open()

        assertEquals(Prefs(), store.prefs())
        assertEquals(emptySet(), store.starredUris())
        assertTrue(lines.any { "unreadable" in it }, lines.toString())
        assertFalse(lines.any { "Tax Return" in it || "content://" in it }, lines.toString())
    }
}
