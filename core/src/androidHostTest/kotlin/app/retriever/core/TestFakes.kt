package app.retriever.core

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.retriever.core.db.IndexDb
import java.util.Collections
import kotlin.math.abs

internal val SIG = Signature("FAKE/vt70/d256")
internal val PROFILE = DeviceProfile(Tier.FULL, ModelVariant.M740, Backend.CPU, 70, 0, true, false)

/**
 * Hashes words into the first [DIMS] dims, the only dims [Vector.of] keeps. Records every input it gets.
 * [before] runs first on every embed, e.g. to hang (a native crash) or to throw.
 */
internal class FakeEmbedder(override val signature: Signature = SIG, private val before: suspend () -> Unit = {}) : Embedder {
    override val backend = Backend.CPU
    val received: MutableList<List<Part>> = Collections.synchronizedList(mutableListOf())
    var closed = false

    override suspend fun embedRaw(parts: List<Part>): FloatArray {
        before()
        if (closed) throw EmbedderClosedException()
        received += parts
        val v = FloatArray(DIMS)
        for (p in parts) {
            when (p) {
                is Part.Text -> for (word in stripPrefix(p.text).split(Regex("[^\\p{L}\\p{Nd}]+"))) {
                    if (word.isNotEmpty()) v[abs(word.lowercase().hashCode()) % DIMS] += 1f
                }

                is Part.Image -> v[p.jpeg.size % DIMS] += 1f

                is Part.Audio -> v[7] += 1f
            }
        }
        if (v.all { it == 0f }) v[0] = 1f
        return v
    }

    private fun stripPrefix(text: String): String {
        val afterQuery = text.lastIndexOf("query: ").let { if (it < 0) -1 else it + "query: ".length }
        val afterText = text.lastIndexOf("text: ").let { if (it < 0) -1 else it + "text: ".length }
        return text.substring(maxOf(afterQuery, afterText, 0))
    }

    override fun close() {
        closed = true
    }
}

internal class FakeFactory(var backendsFor: (ModelBuild) -> List<Backend> = { listOf(Backend.GPU, Backend.CPU) }) : EmbedderFactory {
    val opened = mutableListOf<Pair<DeviceProfile, ModelBuild>>()
    var fail: ((DeviceProfile, ModelBuild) -> Throwable?) = { _, _ -> null }
    var beforeEmbed: suspend () -> Unit = {}
    var last: FakeEmbedder? = null

    override fun backends(build: ModelBuild): List<Backend> = backendsFor(build)

    override fun open(profile: DeviceProfile, build: ModelBuild): Embedder {
        opened += profile to build
        fail(profile, build)?.let { throw it }
        return FakeEmbedder(Signature.of(build, profile.visionTokens, profile.maxTokens)) { beforeEmbed() }.also { last = it }
    }
}

/** [failing] true makes [state] throw, as a dead binder would. */
internal class FakeMonitor(var state: DeviceState = DeviceState(8.0, true, 80, Thermal.NONE), var failing: () -> Boolean = { false }) : DeviceMonitor {
    override fun state(): DeviceState = if (failing()) throw IllegalStateException("binder died") else state
}

/** Returns the pieces listed for a file's name; [onExtract] runs first, e.g. to inspect the store mid-file. */
internal class FakeExtractor(
    private val pieces: Map<String, List<Piece>> = emptyMap(),
    private val fail: Boolean = false,
    private val onExtract: (SourceFile) -> Unit = {},
) : Extractor {
    val names: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> {
        names += file.name
        onExtract(file)
        if (fail) throw IllegalStateException("boom")
        return pieces[file.name] ?: emptyList()
    }
}

/** Returns [next] for every call and records the token each call was given. */
internal class FakeSource : FileSource {
    val calls = mutableListOf<String?>()
    var next = Changes(emptyList(), emptyList(), "tok", isFullScan = true)
    var rules = 1
    override val rulesVersion: Int get() = rules

    override suspend fun changes(since: String?): Changes {
        calls += since
        return next
    }
}

/**
 * [driver], showing each statement's SQL and binders to [onQuery] (reads) or [onExecute] (writes) before it runs; a hook
 * may throw, as a full disk would. Subclass it to watch transactions too.
 */
internal open class SpyDriver(
    private val driver: SqlDriver,
    private val onQuery: (sql: String, binders: (SqlPreparedStatement.() -> Unit)?) -> Unit = { _, _ -> },
    private val onExecute: (sql: String, binders: (SqlPreparedStatement.() -> Unit)?) -> Unit = { _, _ -> },
) : SqlDriver by driver {
    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        onQuery(sql, binders)
        return driver.executeQuery(identifier, sql, mapper, parameters, binders)
    }

    override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
        onExecute(sql, binders)
        return driver.execute(identifier, sql, parameters, binders)
    }
}

/** An in-memory store; with [sig] set, ready for vectors of that signature. */
internal fun newStore(sig: Signature? = SIG, log: (String, Throwable?) -> Unit = { _, _ -> }): Store {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    IndexDb.Schema.create(driver)
    return Store(driver, log).also { if (sig != null) it.resetForModel(sig) }
}

/** How many chunks have a vector; every chunk's file exists in these tests, so it is what search loads. */
internal fun Store.vectorCount(): Int = loadVectors().size

internal fun testFile(name: String, kind: Kind = Kind.DOC, modifiedAt: Long = 1L, folder: String = "", size: Long = 100L, takenAt: Long? = null) =
    SourceFile("content://test/$name", name, folder, "text/plain", kind, size, modifiedAt, takenAt)

internal fun settingsOf(kinds: Set<Kind> = Kind.entries.toSet(), profile: DeviceProfile = PROFILE): () -> IndexSettings = { IndexSettings(profile, kinds) }

internal fun textPiece(text: String, locator: String = "") = Piece(locator, listOf(Part.Text(text)), text, text)

internal val PIXEL = DeviceProbe(Platform.ANDROID, 35, "Google", "Pixel", 8.0, false)

/** A Retriever over [files] that hashes nothing and logs nowhere; [modelReady] stands for the downloaded model file. */
internal fun testRetriever(
    store: Store,
    files: List<SourceFile>,
    extractor: Extractor,
    probe: DeviceProbe = PIXEL,
    factory: FakeFactory = FakeFactory(),
    monitor: FakeMonitor = FakeMonitor(),
    modelReady: (ModelBuild) -> Boolean = { true },
    fewWaitingLimit: Int = 0,
    hasher: ContentHasher = object : ContentHasher {
        override suspend fun quickHash(uri: String, size: Long): String? = null
        override suspend fun fullHash(uri: String): String? = null
    },
): Retriever {
    val source = FakeSource().apply { next = Changes(files, emptyList(), "tok", isFullScan = true) }
    return Retriever(
        store = store,
        source = source,
        extractor = extractor,
        factory = factory,
        monitor = monitor,
        hasher = hasher,
        probe = probe,
        modelReady = modelReady,
        platformReady = { true },
        log = { _, _ -> },
        now = System::currentTimeMillis,
        zoneOffsetMs = { 0L },
        fewWaitingLimit = fewWaitingLimit,
    )
}
