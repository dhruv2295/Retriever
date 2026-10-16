package app.retriever.core

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.retriever.core.db.IndexDb
import co.touchlab.sqliter.JournalMode

/** The index database for the iOS app, in WAL mode like Android's, so the UI reads while a batch writes. */
fun iosStore(log: (String, Throwable?) -> Unit): Store = Store(
    NativeSqliteDriver(IndexDb.Schema, Store.DB_NAME, onConfiguration = { it.copy(journalMode = JournalMode.WAL) }),
    log,
)
