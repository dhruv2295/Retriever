package app.retriever.core

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.inMemoryDriver
import app.retriever.core.db.IndexDb

actual fun testDriver(): SqlDriver = inMemoryDriver(IndexDb.Schema)
