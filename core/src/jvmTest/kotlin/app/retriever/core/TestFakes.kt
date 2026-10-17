package app.retriever.core

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
        for (p in parts) when (p) {
            is Part.Text -> for (word in stripPrefix(p.text).split(Regex("[^\\p{L}\\p{Nd}]+"))) {
                if (word.isNotEmpty()) v[abs(word.lowercase().hashCode()) % DIMS] += 1f
            }
            is Part.Image -> v[p.jpeg.size % DIMS] += 1f
            is Part.Audio -> v[7] += 1f
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

internal class FakeMonitor(var state: DeviceState = DeviceState(8.0, true, 80, Thermal.NONE)) : DeviceMonitor {
    override fun state(): DeviceState = state
}

/** Returns the pieces listed for a file's name; [onExtract] runs first, e.g. to inspect the store mid-file. */
internal class FakeExtractor(
    private val pieces: Map<String, List<Piece>> = emptyMap(),
    private val fail: Boolean = false,
    private val onExtract: (SourceFile) -> Unit = {},
) : Extractor {
    val names = mutableListOf<String>()

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

/** An in-memory store; with [sig] set, ready for vectors of that signature. */
internal fun newStore(sig: Signature? = SIG, log: (String, Throwable?) -> Unit = { _, _ -> }): Store {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    IndexDb.Schema.create(driver)
    return Store(driver, log).also { if (sig != null) it.resetForModel(sig) }
}

internal fun testFile(
    name: String,
    kind: Kind = Kind.DOC,
    modifiedAt: Long = 1L,
    folder: String = "",
    size: Long = 100L,
    takenAt: Long? = null,
) = SourceFile("content://test/$name", name, folder, "text/plain", kind, size, modifiedAt, takenAt)

internal fun settingsOf(kinds: Set<Kind> = Kind.entries.toSet(), profile: DeviceProfile = PROFILE): () -> IndexSettings =
    { IndexSettings(profile, kinds) }

internal fun textPiece(text: String, locator: String = "") = Piece(locator, listOf(Part.Text(text)), null)

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
): Retriever {
    val source = FakeSource().apply { next = Changes(files, emptyList(), "tok", isFullScan = true) }
    val noHashes = object : ContentHasher {
        override suspend fun quickHash(uri: String, size: Long): String? = null
        override suspend fun fullHash(uri: String): String? = null
    }
    return Retriever(store, source, extractor, factory, monitor, noHashes, probe, modelReady, { true }, { _, _ -> }, System::currentTimeMillis, { 0L })
}
