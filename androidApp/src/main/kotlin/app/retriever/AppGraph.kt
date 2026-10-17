package app.retriever

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE
import android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO
import android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
import android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.retriever.core.Backend
import app.retriever.core.CompositeSource
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
import app.retriever.core.android.SafSource
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
    val source: FileSource = demo?.source ?: CompositeSource(
        mapOf(
            "content://media/" to MediaStoreSource(app, ::mediaAccess),
            SafSource.PREFIX to SafSource(app, { access().trees }, { access().mediaKinds }, ::mediaListed),
        ),
    )
    val extractor: Extractor = demo?.extractor ?: AndroidExtractor(app)

    /** For work that must outlive a screen (setup, Tune again, the Pause action) and stay off the main thread. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // The driver opens the file on first query, so building it here touches no disk.
    private val store: Store = run {
        // WAL lets the UI read while the indexer holds a write transaction, instead of queueing behind it.
        val callback = object : AndroidSqliteDriver.Callback(IndexDb.Schema) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                db.enableWriteAheadLogging()
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                val start = System.nanoTime()
                super.onUpgrade(db, oldVersion, newVersion)
                Log.i("Retriever", "Index upgraded $oldVersion -> $newVersion in ${(System.nanoTime() - start) / 1_000_000} ms")
            }
        }
        Store(AndroidSqliteDriver(IndexDb.Schema, app, demo?.dbName ?: Store.DB_NAME, callback = callback), ::log)
    }

    /** Read fresh on every call: the person can change any of it in Settings while the app runs. */
    fun access(): Access = if (demo != null) Access.ALL else Access.of(
        Build.VERSION.SDK_INT,
        Environment.isExternalStorageManager(),
        { ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED },
        app.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() },
        SafSource.volumeDescriber(app),
    )

    /** Some access to files; the worker reads it too, since names are synced before the model arrives. */
    val platformReady: () -> Boolean = { access().ready }

    /** Keeps a folder from the system picker; returns why it can't be added, or null. */
    fun addFolder(uri: Uri): String? {
        val folders = access().folders
        Access.refusePick(uri.toString(), folders)?.let { return it }
        val resolver = app.contentResolver
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        try {
            // Write lets Duplicates delete copies there; a provider may grant read only.
            runCatching { resolver.takePersistableUriPermission(uri, read or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                .getOrElse { resolver.takePersistableUriPermission(uri, read) }
        } catch (e: SecurityException) {
            log("folder not kept", e)
            return "Couldn't keep access to that folder. Try picking it again."
        }
        // The new folder covers any added inside it.
        Access.inside(uri.toString(), folders).forEach { release(it.uri) }
        accessChanged()
        return null
    }

    fun removeFolder(uri: String) {
        release(uri)
        accessChanged()
    }

    private fun release(uri: String) {
        val grant = app.contentResolver.persistedUriPermissions.firstOrNull { it.uri.toString() == uri } ?: return
        val flags = (if (grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        app.contentResolver.releasePersistableUriPermission(grant.uri, flags)
    }

    /** A folder or permission changed: sync now, or at the end of the batch already running. */
    fun accessChanged() {
        retriever.notifyChanged()
        IndexScheduler.indexNow(app)
    }

    /** Picking more photos reveals old rows without changing a permission, so the picked set is part of it. */
    private fun mediaAccess(): String {
        val a = access()
        if (a.photos != Photos.SELECTED) return a.fingerprint
        // Videos too unless all of them are allowed: Android's picker hands out both.
        val types = if (a.videos) "$MEDIA_TYPE_IMAGE" else "$MEDIA_TYPE_IMAGE, $MEDIA_TYPE_VIDEO"
        var picked = 0L
        app.contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            arrayOf(MediaStore.MediaColumns._ID),
            "$MEDIA_TYPE IN ($types)",
            null,
            MediaStore.MediaColumns._ID,
        )?.use { c -> while (c.moveToNext()) picked = picked * 31 + c.getLong(0) }
        return "${a.fingerprint}-$picked"
    }

    /**
     * What MediaStore lists beyond the kinds it covers in full ([Access.mediaKinds]), e.g. the photos the person
     * picked, as [SafSource.mediaKey]s: a picked folder skips them, so one photo never has two rows.
     */
    private fun mediaListed(): Set<String> {
        val a = access()
        val covered = buildList {
            if (a.photos == Photos.ALL) add(MEDIA_TYPE_IMAGE)
            if (a.videos) add(MEDIA_TYPE_VIDEO)
            if (a.audio) add(MEDIA_TYPE_AUDIO)
        }
        val keys = HashSet<String>()
        app.contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            arrayOf(MediaStore.MediaColumns.VOLUME_NAME, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME),
            if (covered.isEmpty()) null else "$MEDIA_TYPE NOT IN (${covered.joinToString()})",
            null,
            null,
        )?.use { c ->
            while (c.moveToNext()) keys += SafSource.mediaKey(c.getString(0).orEmpty(), c.getString(1).orEmpty(), c.getString(2).orEmpty())
        }
        return keys
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
        platformReady = platformReady,
        log = ::log,
        now = System::currentTimeMillis,
        zoneOffsetMs = { java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong() },
    )

    val setup by lazy { SetupFlow(this) }

    /** Off the main thread, once per process: opens the database and settles [Retriever.setUp]. */
    suspend fun start() {
        withContext(Dispatchers.IO) {
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
