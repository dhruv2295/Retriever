@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.retriever.core

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.wrapConnection
import app.retriever.core.db.IndexDb
import co.touchlab.sqliter.DatabaseConnection
import co.touchlab.sqliter.JournalMode
import co.touchlab.sqliter.SynchronousFlag
import co.touchlab.sqliter.getVersion
import co.touchlab.sqliter.setVersion
import co.touchlab.sqliter.withTransaction
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * The index database in [dir], in WAL mode with reader connections so the UI reads while a batch writes. Kept out of
 * backups: the index is rebuilt from the files. Settings and stars go beside [dir], in "<dir>-user-state.json", so
 * they are backed up and each test's temporary directory has its own. Tests pass a temporary directory.
 */
fun iosStore(dir: String, log: (String, Throwable?) -> Unit): Store {
    NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
    NSURL.fileURLWithPath(dir).setResourceValue(true, NSURLIsExcludedFromBackupKey, null)
    val driver = iosIndexDriver(dir)
    val statePath = dir.trimEnd('/') + "-user-state.json"
    val userState = UserStateFile(
        read = { NSString.stringWithContentsOfFile(statePath, NSUTF8StringEncoding, null) },
        write = { text ->
            // atomically: Foundation writes a temporary file and renames it over the old one.
            check(NSString.create(string = text).writeToFile(statePath, true, NSUTF8StringEncoding, null)) { "write failed" }
        },
    )
    return Store(driver, log, userState = userState)
}

/** The index database in [dir], opened as the app opens it. */
internal fun iosIndexDriver(dir: String): SqlDriver = NativeSqliteDriver(IndexDb.Schema, Store.DB_NAME, maxReaderConnections = 4, onConfiguration = {
    it.copy(
        journalMode = JournalMode.WAL,
        extendedConfig = it.extendedConfig.copy(basePath = dir, synchronousFlag = SynchronousFlag.NORMAL),
        lifecycleConfig = it.lifecycleConfig.copy(onCreateConnection = ::configure),
    )
})

/**
 * Each new connection, before sqliter checks the schema version: an index from a newer build (a TestFlight downgrade)
 * is made again, as Android's onDowngrade does, since sqliter 1.3 (SQLDelight 2.4's) has no downgrade hook and would
 * throw on every open. No journal_size_limit: Apple's SQLite already truncates the -wal file to 32 KB (IosStoreTest).
 */
private fun configure(connection: DatabaseConnection) {
    val version = IndexDb.Schema.version.toInt()
    if (connection.getVersion() <= version) return
    connection.withTransaction { c ->
        wrapConnection(c) { Store.resetSchema(it) }
        c.setVersion(version)
    }
}

/** Application Support/Retriever, the app's own data directory. */
fun appDataDir(): String = checkNotNull(
    NSFileManager.defaultManager.URLForDirectory(NSApplicationSupportDirectory, NSUserDomainMask, null, true, null)
        ?.URLByAppendingPathComponent("Retriever")?.path,
) { "no Application Support directory" }
