package app.retriever.di

import android.Manifest
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.util.AtomicFile
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.retriever.BrowseViewModel
import app.retriever.BuildConfig
import app.retriever.Calibrator
import app.retriever.Demo
import app.retriever.DuplicatesViewModel
import app.retriever.FoldersViewModel
import app.retriever.IndexScheduler
import app.retriever.LaunchQuery
import app.retriever.ModelDownload
import app.retriever.PlatformReady
import app.retriever.RecentShortcuts
import app.retriever.SearchViewModel
import app.retriever.SettingsViewModel
import app.retriever.SetupFlow
import app.retriever.StarHolder
import app.retriever.StatusViewModel
import app.retriever.core.CompositeSource
import app.retriever.core.CoreDispatchers
import app.retriever.core.EmbedderFactory
import app.retriever.core.Extractor
import app.retriever.core.FileSource
import app.retriever.core.ModelBuild
import app.retriever.core.Retriever
import app.retriever.core.Store
import app.retriever.core.UserStateFile
import app.retriever.core.android.AndroidContentHasher
import app.retriever.core.android.AndroidDeviceMonitor
import app.retriever.core.android.AndroidDeviceProbe
import app.retriever.core.android.AndroidExtractor
import app.retriever.core.android.MediaDecoding
import app.retriever.core.android.MediaPipeEmbedderFactory
import app.retriever.core.android.MediaStoreSource
import app.retriever.core.android.SafSource
import app.retriever.core.db.IndexDb
import app.retriever.data.AccessService
import app.retriever.data.FileOpener
import app.retriever.data.IndexService
import app.retriever.data.LogSink
import app.retriever.data.MediaDeleter
import app.retriever.data.ModelRepository
import app.retriever.data.StorageService
import app.retriever.photoReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File
import java.io.FileNotFoundException

/** The app-wide coroutine scope, for work that must outlive a screen (setup, Tune again, Pause) and stay off the main thread. */
val AppScope = named("app")

/** Where the model files live. */
val ModelDir = named("modelDir")

/** What a debug build's demo mode swaps in, so screens can be checked without the model. */
data class DemoParts(
    val source: FileSource,
    val extractor: Extractor,
    val factory: EmbedderFactory,
    val dbName: String,
    val modelReady: (ModelBuild) -> Boolean,
)

/** Demo parts, or null in a normal run; a holder because a Koin definition can't be null. */
class DemoMode(val parts: DemoParts?)

private val log: (String, Throwable?) -> Unit = { message, t -> LogSink.write(BuildConfig.DEBUG, message, t) }

// Every single is lazy: the first get, from RetrieverApp's IO start, does the disk reads off the main thread.
val appModule = module {
    single(AppScope) { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    single { DemoMode(Demo.parts(androidContext())) }
    single(ModelDir) { androidContext().getExternalFilesDir("models")!! }
    single { ModelDownload(androidContext(), get(ModelDir)) }
    single { AndroidDeviceProbe(androidContext()).probe() }
    single {
        val app = androidContext()
        AccessService(app, demo = get<DemoMode>().parts != null) {
            get<Retriever>().notifyChanged()
            IndexScheduler.indexNow(app)
        }
    }
    // Some access to files; the worker reads it too, since names are synced before the model arrives.
    single<() -> Boolean>(PlatformReady) {
        val access = get<AccessService>()
        val ready: () -> Boolean = { access.access().ready }
        ready
    }
    single<FileSource> {
        val access = get<AccessService>()
        get<DemoMode>().parts?.source ?: CompositeSource(
            mapOf(
                "content://media/" to MediaStoreSource(androidContext(), access::mediaAccess, log),
                SafSource.PREFIX to SafSource(androidContext(), { access.access().trees }, { access.access().mediaKinds }, access::mediaListed, log),
            ),
        )
    }
    single<Extractor> { get<DemoMode>().parts?.extractor ?: AndroidExtractor(androidContext()).also { MediaDecoding.log = log } }
    // The driver opens the file on first query, so building it touches no disk.
    single {
        val app = androidContext()
        val demo = get<DemoMode>().parts
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
        // Backed up and moved to a new phone (data_extraction_rules.xml); demo mode keeps the person's state out of its database.
        val userState = if (demo != null) null else AtomicFile(File(app.filesDir, "user-state.json")).let { f ->
            UserStateFile(
                read = {
                    try {
                        f.readFully().decodeToString()
                    } catch (e: FileNotFoundException) {
                        null
                    }
                },
                write = { text ->
                    val out = f.startWrite()
                    try {
                        out.write(text.encodeToByteArray())
                        f.finishWrite(out)
                    } catch (e: Exception) {
                        f.failWrite(out)
                        throw e
                    }
                },
            )
        }
        Store(AndroidSqliteDriver(IndexDb.Schema, app, demo?.dbName ?: Store.DB_NAME, callback = callback), log, userState = userState)
    }
    single {
        val app = androidContext()
        val demo = get<DemoMode>().parts
        Retriever(
            store = get(),
            source = get(),
            extractor = get(),
            factory = demo?.factory ?: MediaPipeEmbedderFactory(app, get(ModelDir), get()),
            monitor = AndroidDeviceMonitor(app),
            hasher = AndroidContentHasher(app),
            probe = get(),
            modelReady = demo?.modelReady ?: get<ModelDownload>()::ready,
            platformReady = get(PlatformReady),
            log = log,
            now = System::currentTimeMillis,
            zoneOffsetMs = { java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong() },
            dispatchers = CoreDispatchers(),
        )
    }

    single { IndexService(androidContext(), get()) }
    single { StorageService(androidContext(), get(), get(), get(ModelDir)) }
    single { FileOpener(androidContext()) }
    single { MediaDeleter(androidContext()) }
    singleOf(::LaunchQuery)
    singleOf(::RecentShortcuts)
    singleOf(::Calibrator)
    single { photoReader(androidContext()) }
    single { ModelRepository(androidContext(), get(), get(), get(AppScope)) }
    single {
        val app = androidContext()
        val calibrator = get<Calibrator>()
        SetupFlow(
            get(), get(), get(), get(), get(), get(AppScope),
            onMeteredNetwork = { app.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered },
            // Below Android 13 notifications need no permission, so there is nothing to ask.
            notificationsGranted = {
                Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            },
            calibrate = { calibrator.calibrate(tuneAgain = false) },
        )
    }

    viewModelOf(::BrowseViewModel)
    viewModelOf(::StarHolder)
    viewModelOf(::FoldersViewModel)
    viewModelOf(::DuplicatesViewModel)
    viewModelOf(::StatusViewModel)
    viewModel {
        val calibrator = get<Calibrator>()
        SettingsViewModel(get(), get(), get(), get(), get(AppScope)) { calibrator.calibrate(tuneAgain = true) }
    }
    viewModelOf(::SearchViewModel)
}
