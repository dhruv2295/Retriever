package app.retriever.core

import app.cash.sqldelight.db.SqlDriver

/** A fresh in-memory database with the schema created, on the platform's own SQLite. */
expect fun testDriver(): SqlDriver
