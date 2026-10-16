package app.rummage

import android.content.Context
import android.os.Environment
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.rummage.core.Backend
import app.rummage.core.DeviceProfile
import app.rummage.core.Embedder
import app.rummage.core.LibrarySync
import app.rummage.core.ModelVariant
import app.rummage.core.SearchEngine
import app.rummage.core.Store
import app.rummage.core.android.AndroidContentHasher
import app.rummage.core.android.AndroidDeviceMonitor
import app.rummage.core.android.AndroidExtractor
import app.rummage.core.android.MediaPipeEmbedderFactory
import app.rummage.core.android.MediaStoreSource
import app.rummage.core.db.IndexDb
import app.rummage.core.effective
import app.rummage.core.prefs
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class IndexState(
    val running: Boolean = false,
    val processed: Int = 0,
    val failed: Int = 0,
    val pendingAtStart: Long = 0,
    val current: String? = null,
    val lastStop: String? = null,
    val lastError: String? = null,
)

class AppGraph(val app: Context) {
    val modelDir: File = app.getExternalFilesDir("models")!!
    // WAL lets the UI read while the indexer holds a write transaction, instead of queueing behind it.
    val store: Store by lazy {
        val callback = object : AndroidSqliteDriver.Callback(IndexDb.Schema) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                db.enableWriteAheadLogging()
            }
        }
        Store(AndroidSqliteDriver(IndexDb.Schema, app, "library.db", callback = callback))
    }
    val monitor: AndroidDeviceMonitor by lazy { AndroidDeviceMonitor(app) }
    val extractor: AndroidExtractor by lazy { AndroidExtractor(app) }
    val factory: MediaPipeEmbedderFactory by lazy { MediaPipeEmbedderFactory(app, modelDir) }
    val source: MediaStoreSource by lazy { MediaStoreSource(app) }
    val hasher: AndroidContentHasher by lazy { AndroidContentHasher(app) }
    val sync: LibrarySync by lazy { LibrarySync(store, source, System::currentTimeMillis) }
    val indexState = MutableStateFlow(IndexState())

    /** Held for a whole indexing batch, and by calibration so it never runs alongside one. */
    val indexLock = Mutex()

    /** For work that must outlive a screen (Tune again) or stay off the main thread. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** True while SpikeActivity is visible: it runs its own model, so product indexing waits. */
    @Volatile
    var spikeActive = false

    /** Set when files may have changed during a batch; the batch then schedules a follow-up sync. */
    @Volatile
    var changedSinceSync = false

    private data class Key(val model: ModelVariant, val backend: Backend, val visionTokens: Int)

    private val embedderLock = Mutex()
    private var embedder: Embedder? = null
    private var key: Key? = null
    @Volatile
    private var engine: SearchEngine? = null
    private var engineEmbedder: Embedder? = null

    fun modelFile(): File = File(modelDir, ModelVariant.M740.fileName)

    fun isSetUp(): Boolean =
        Environment.isExternalStorageManager() && modelFile().length() == MODEL_BYTES && store.profile() != null

    fun effectiveProfile(): DeviceProfile? = store.profile()?.let { store.prefs().effective(it) }

    /** One embedder for indexing and search: two copies of the model would double memory. */
    suspend fun embedder(): Embedder = embedderLock.withLock {
        val p = effectiveProfile() ?: throw IllegalStateException("Rummage isn't set up yet")
        if (modelFile().length() != MODEL_BYTES) throw IllegalStateException("Rummage isn't set up yet")
        val want = Key(p.model, p.backend, p.visionTokens)
        embedder?.takeIf { key == want }?.let { return@withLock it }
        // The old embedder's close() waits for any embed in flight, then later calls fail fast.
        withContext(Dispatchers.Default) {
            embedder?.close()
            embedder = null
            key = null
            factory.open(p.model, p.backend, p.visionTokens)
        }.also {
            embedder = it
            key = want
        }
    }

    suspend fun releaseEmbedder() = embedderLock.withLock {
        withContext(Dispatchers.Default) { embedder?.close() }
        embedder = null
        key = null
        engine = null
        engineEmbedder = null
    }

    suspend fun searchEngine(): SearchEngine {
        val e = embedder()
        return embedderLock.withLock {
            engine?.takeIf { engineEmbedder === e } ?: SearchEngine(store, e).also {
                engine = it
                engineEmbedder = e
            }
        }
    }

    /** A rebuilt engine loads the vector table fresh on its first search. */
    fun notifyIndexChanged() {
        engine = null
    }

    companion object {
        const val MODEL_BYTES = 484_622_336L
    }
}
