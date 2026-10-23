package app.retriever.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.retriever.core.Store
import app.retriever.core.UserStateFile
import app.retriever.core.db.IndexDb
import app.retriever.core.prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemProperties

/** The index database as the app opens it, on the platform's own SQLite. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35], application = android.app.Application::class)
class IndexDriverTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After fun cleanUp() {
        ShadowSystemProperties.reset()
        context.deleteDatabase(NAME)
    }

    /** NORMAL (1) under WAL: a sync at checkpoints instead of every commit, even where the device's own default is FULL. */
    @Test fun commitsDoNotSyncEachTime() {
        ShadowSystemProperties.override("debug.sqlite.wal.syncmode", "FULL")
        indexDriver(context, NAME).use { driver ->
            assertEquals("wal", string(driver, "PRAGMA journal_mode"))
            assertEquals("1", string(driver, "PRAGMA synchronous"))
        }
    }

    /** An older build opening a newer build's index makes the index again, and settings and stars come back from their file. */
    @Test fun aNewerIndexIsMadeAgainKeepingSettingsAndStars() {
        context.openOrCreateDatabase(NAME, Context.MODE_PRIVATE, null).use {
            it.execSQL("CREATE TABLE future (x INTEGER)")
            it.execSQL("CREATE VIRTUAL TABLE chunk_fts USING fts4(text, labels)")
            it.version = IndexDb.Schema.version.toInt() + 1
        }
        val saved = """{"prefs":{"excludedFolders":["Private"]},"starred":[{"uri":"content://media/external/file/7","starredAt":1,""" +
            """"name":"a.pdf","size":10,"modifiedAt":2}]}"""
        indexDriver(context, NAME).use { driver ->
            val store = Store(driver, userState = UserStateFile(read = { saved }, write = {}))
            assertEquals(setOf("Private"), store.prefs().excludedFolders)
            assertEquals(0L, store.fileCount())
            assertEquals("content://media/external/file/7", string(driver, "SELECT uri FROM starred"))
            assertEquals(IndexDb.Schema.version.toString(), string(driver, "PRAGMA user_version"))
            assertEquals("0", string(driver, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'future'"))
        }
    }

    private fun string(driver: SqlDriver, sql: String): String? = driver.executeQuery(null, sql, { c ->
        c.next()
        QueryResult.Value(c.getString(0))
    }, 0).value

    private companion object {
        const val NAME = "index-driver-test.db"
    }
}
