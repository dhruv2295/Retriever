package app.retriever

import app.cash.sqldelight.Query
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.retriever.core.Backend
import app.retriever.core.Changes
import app.retriever.core.ContentHasher
import app.retriever.core.CoreDispatchers
import app.retriever.core.DeviceMonitor
import app.retriever.core.DeviceProbe
import app.retriever.core.DeviceProfile
import app.retriever.core.DeviceState
import app.retriever.core.Embedder
import app.retriever.core.EmbedderFactory
import app.retriever.core.Extractor
import app.retriever.core.FileSource
import app.retriever.core.Kind
import app.retriever.core.ModelBuild
import app.retriever.core.Piece
import app.retriever.core.Platform
import app.retriever.core.Retriever
import app.retriever.core.SourceFile
import app.retriever.core.Store
import app.retriever.core.Thermal
import app.retriever.core.db.IndexDb

fun testFile(name: String, kind: Kind) = SourceFile("content://test/$name", name, "", "application/octet-stream", kind, 100, 1)

/**
 * A real Retriever over an in-memory store, listing [files] once. With no [embedder] there is no model and search
 * answers from file names; [profile] is stored first, as if the phone had been tuned. [ramGb] below the floor makes
 * a words-only phone. [dispatchers] puts the retriever on a test dispatcher.
 */
fun testRetriever(
    files: List<SourceFile> = emptyList(),
    ramGb: Double = 8.0,
    extract: suspend (SourceFile) -> List<Piece> = { emptyList() },
    embedder: Embedder? = null,
    beforeOpen: () -> Unit = {},
    profile: DeviceProfile? = null,
    dispatchers: CoreDispatchers = CoreDispatchers(),
    wrapDriver: (SqlDriver) -> SqlDriver = { it },
    pluggedIn: () -> Boolean = { true },
): Retriever {
    // DriverManager registers the SQLite driver once per JVM, from the first Robolectric sandbox's class loader; a test in
    // another sandbox (NATIVE graphics) can't see that one, so load the driver class in this sandbox too.
    Class.forName("org.sqlite.JDBC")
    val store = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).let {
        IndexDb.Schema.create(it)
        Store(wrapDriver(it))
    }
    profile?.let(store::setProfile)
    return Retriever(
        store = store,
        source = object : FileSource {
            override suspend fun changes(since: String?) = Changes(files, emptyList(), "tok", isFullScan = true)
        },
        extractor = object : Extractor {
            override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> = extract(file)
        },
        factory = object : EmbedderFactory {
            override fun backends(build: ModelBuild) = listOf(Backend.CPU)
            override fun open(profile: DeviceProfile, build: ModelBuild): Embedder = (embedder ?: error("no model in this test")).also { beforeOpen() }
        },
        monitor = object : DeviceMonitor {
            override fun state() = DeviceState(ramGb, pluggedIn(), 80, Thermal.NONE)
        },
        hasher = object : ContentHasher {
            override suspend fun quickHash(uri: String, size: Long): String? = null
            override suspend fun fullHash(uri: String): String? = null
        },
        probe = DeviceProbe(Platform.ANDROID, 35, "Google", "Pixel", ramGb, false),
        modelReady = { embedder != null },
        platformReady = { true },
        log = { _, _ -> },
        now = System::currentTimeMillis,
        zoneOffsetMs = { 0L },
        dispatchers = dispatchers,
    )
}

/**
 * Counts the live-query subscriptions the store is asked for, and the reads of the oldest photo that start On this day;
 * a read whose SQL holds any of [failing] throws, as a broken database would.
 */
internal class CountingDriver(private val driver: SqlDriver) : SqlDriver by driver {
    var subscriptions = 0
    var memoryProbes = 0
    var failing = emptySet<String>()

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        if (sql.contains("MIN(COALESCE")) memoryProbes++
        check(failing.none(sql::contains)) { "disk I/O error" }
        return driver.executeQuery(identifier, sql, mapper, parameters, binders)
    }

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        subscriptions++
        driver.addListener(*queryKeys, listener = listener)
    }
}
