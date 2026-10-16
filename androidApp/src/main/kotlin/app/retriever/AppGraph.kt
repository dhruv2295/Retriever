package app.retriever

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.retriever.core.Backend
import app.retriever.core.DeviceProfile
import app.retriever.core.EmbedderFactory
import app.retriever.core.Extractor
import app.retriever.core.FileSource
import app.retriever.core.ModelBuild
import app.retriever.core.ModelBuilds
import app.retriever.core.Retriever
import app.retriever.core.Store
import app.retriever.core.Tier
import app.retriever.core.android.AndroidContentHasher
import app.retriever.core.android.AndroidDeviceMonitor
import app.retriever.core.android.AndroidDeviceProbe
import app.retriever.core.android.AndroidExtractor
import app.retriever.core.android.MediaPipeEmbedderFactory
import app.retriever.core.android.MediaStoreSource
import app.retriever.core.db.IndexDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File

/** What a debug build's demo mode swaps in, so screens can be checked without the model. */
data class DemoParts(
    val source: FileSource,
    val extractor: Extractor,
    val factory: EmbedderFactory,
    val dbName: String,
    val modelReady: (ModelBuild) -> Boolean,
)

class AppGraph(val app: Context) {
    val modelDir: File = app.getExternalFilesDir("models")!!
    val download = ModelDownload(app, modelDir)
    private val demo: DemoParts? = Demo.parts(app)
    private val probe = AndroidDeviceProbe(app).probe()
    val source: FileSource = demo?.source ?: MediaStoreSource(app)
    val extractor: Extractor = demo?.extractor ?: AndroidExtractor(app)

    /** For work that must outlive a screen (Tune again, the Pause action) and stay off the main thread. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // The driver opens the file on first query, so building it here touches no disk.
    private val store: Store = run {
        // WAL lets the UI read while the indexer holds a write transaction, instead of queueing behind it.
        val callback = object : AndroidSqliteDriver.Callback(IndexDb.Schema) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                db.enableWriteAheadLogging()
            }
        }
        Store(AndroidSqliteDriver(IndexDb.Schema, app, demo?.dbName ?: Store.DB_NAME, callback = callback), ::log)
    }

    val retriever = Retriever(
        store = store,
        source = source,
        extractor = extractor,
        factory = demo?.factory ?: MediaPipeEmbedderFactory(app, modelDir, probe),
        monitor = AndroidDeviceMonitor(app),
        hasher = AndroidContentHasher(app),
        probe = probe,
        modelReady = demo?.modelReady ?: download::ready,
        platformReady = if (demo != null) ({ true }) else Environment::isExternalStorageManager,
        log = ::log,
        now = System::currentTimeMillis,
    )

    /** Off the main thread, once per process: opens the database and settles [Retriever.setUp]. */
    suspend fun start() {
        withContext(Dispatchers.IO) {
            Store.OLD_DB_NAMES.forEach { app.deleteDatabase(it) }
            if (demo != null && store.profile() == null) {
                store.setProfile(DeviceProfile(Tier.BALANCED, ModelBuilds.variantFor(probe), Backend.CPU, 70, 5, false, false))
            }
        }
        retriever.refreshSetUp()
    }

    private fun log(message: String, t: Throwable?) {
        Log.w("Retriever", message, t)
    }
}
