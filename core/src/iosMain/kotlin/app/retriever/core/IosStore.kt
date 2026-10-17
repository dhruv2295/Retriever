@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.retriever.core

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.retriever.core.db.IndexDb
import co.touchlab.sqliter.JournalMode
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
    val driver = NativeSqliteDriver(IndexDb.Schema, Store.DB_NAME, maxReaderConnections = 4, onConfiguration = {
        it.copy(journalMode = JournalMode.WAL, extendedConfig = it.extendedConfig.copy(basePath = dir))
    })
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

/** Application Support/Retriever, the app's own data directory. */
fun appDataDir(): String = checkNotNull(
    NSFileManager.defaultManager.URLForDirectory(NSApplicationSupportDirectory, NSUserDomainMask, null, true, null)
        ?.URLByAppendingPathComponent("Retriever")?.path,
) { "no Application Support directory" }
