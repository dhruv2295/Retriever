@file:OptIn(ExperimentalForeignApi::class)

package app.retriever.core

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.retriever.core.db.IndexDb
import co.touchlab.sqliter.JournalMode
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask

/**
 * The index database under Application Support/Retriever, in WAL mode with reader connections so the UI reads
 * while a batch writes. Kept out of backups: the index is rebuilt from the files.
 */
fun iosStore(log: (String, Throwable?) -> Unit): Store {
    val fm = NSFileManager.defaultManager
    val dir = checkNotNull(
        fm.URLForDirectory(NSApplicationSupportDirectory, NSUserDomainMask, null, true, null)
            ?.URLByAppendingPathComponent("Retriever"),
    ) { "no Application Support directory" }
    fm.createDirectoryAtURL(dir, true, null, null)
    dir.setResourceValue(true, NSURLIsExcludedFromBackupKey, null)
    val driver = NativeSqliteDriver(IndexDb.Schema, Store.DB_NAME, maxReaderConnections = 4, onConfiguration = {
        it.copy(journalMode = JournalMode.WAL, extendedConfig = it.extendedConfig.copy(basePath = dir.path))
    })
    return Store(driver, log)
}
