/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/smartalbum/SmartAlbumIndexingWorker.kt (Apache-2.0). Modified for Retriever: the per-photo loop, input data, setProgress, coordinator pause and sample branch are replaced by one Retriever.indexBatch, foreground services for visible, trial and charger runs, and follow-up scheduling by NextRun.

package app.retriever.di

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.util.AtomicFile
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.retriever.BuildConfig
import app.retriever.Demo
import app.retriever.IndexWorker
import app.retriever.access.AccessService
import app.retriever.browse.BrowseViewModel
import app.retriever.core.CompositeSource
import app.retriever.core.ContentHasher
import app.retriever.core.CoreDispatchers
import app.retriever.core.DeviceMonitor
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
import app.retriever.core.android.AndroidFrameSource
import app.retriever.core.android.MediaPipeEmbedderFactory
import app.retriever.core.android.MediaStoreSource
import app.retriever.core.android.SafSource
import app.retriever.core.db.IndexDb
import app.retriever.core.indexingDispatcher
import app.retriever.core.readAheadDispatcher
import app.retriever.diagnostics.ExitReasons
import app.retriever.diagnostics.LogSink
import app.retriever.duplicates.DuplicatesViewModel
import app.retriever.duplicates.MediaDeleter
import app.retriever.folders.FoldersViewModel
import app.retriever.home.HomeViewModel
import app.retriever.indexing.IndexService
import app.retriever.model.Calibrator
import app.retriever.model.ModelDownload
import app.retriever.model.ModelRepository
import app.retriever.model.UpgradeFlow
import app.retriever.search.LaunchQuery
import app.retriever.search.RecentShortcuts
import app.retriever.search.SearchViewModel
import app.retriever.search.photoReader
import app.retriever.settings.SettingsViewModel
import app.retriever.settings.StorageService
import app.retriever.setup.SetupFlow
import app.retriever.starred.StarHolder
import app.retriever.status.StatusViewModel
import app.retriever.viewer.DocViewModel
import app.retriever.viewer.PlayerViewModel
import app.retriever.viewer.ViewerQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.dsl.worker
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

/**
 * Whether the app can see any files yet; the worker syncs names before the model arrives. Declared above [appModule]:
 * top-level properties initialise in file order, so a qualifier declared below the module is still null when it registers.
 */
val PlatformReady = named("platformReady")

private val log: (String, Throwable?) -> Unit = { message, t -> LogSink.write(BuildConfig.DEBUG, message, t) }

/**
 * The index database [name], opened as the app opens it; the driver opens the file on first query, so building it touches
 * no disk. WAL lets the UI read while the indexer holds a write transaction, instead of queueing behind it.
 */
fun indexDriver(context: Context, name: String): SqlDriver {
    val callback = object : AndroidSqliteDriver.Callback(IndexDb.Schema) {
        override fun onConfigure(db: SupportSQLiteDatabase) {
            db.enableWriteAheadLogging()
            // With WAL, NORMAL syncs at checkpoints instead of every commit and loses nothing to an app crash
            // (developer.android.com/topic/performance/sqlite-performance-best-practices). AOSP's WAL default, set here
            // because a device's own configuration can choose FULL.
            db.execSQL("PRAGMA synchronous = NORMAL")
        }

        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
            val start = System.nanoTime()
            super.onUpgrade(db, oldVersion, newVersion)
            if (BuildConfig.DEBUG) Log.i("Retriever", "Index upgraded $oldVersion -> $newVersion in ${(System.nanoTime() - start) / 1_000_000} ms")
        }

        // An older build over a newer index (adb install -d): made again from the files, not a crash on every open.
        override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
            Store.resetSchema(AndroidSqliteDriver(db))
            if (BuildConfig.DEBUG) Log.i("Retriever", "Index $oldVersion made again as $newVersion")
        }
    }
    return AndroidSqliteDriver(IndexDb.Schema, context, name, callback = callback)
}

// Every single is lazy: the first get, from RetrieverApp's IO start, does the disk reads off the main thread.
val appModule = module {
    single(AppScope) { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    singleOf(::ViewerQueue)
    single { app.retriever.files.FileActions() }
    single { DemoMode(Demo.parts(androidContext())) }
    // Null while shared storage is unavailable; the app's own storage then holds the model.
    single(ModelDir) { androidContext().let { it.getExternalFilesDir("models") ?: File(it.filesDir, "models").apply { mkdirs() } } }
    single { ModelDownload(androidContext(), get(ModelDir)) }
    single { AndroidDeviceProbe(androidContext()).probe() }
    single {
        val app = androidContext()
        AccessService(app, demo = get<DemoMode>().parts != null) { get<IndexService>().indexNow() }
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
    single<Extractor> { get<DemoMode>().parts?.extractor ?: AndroidExtractor(androidContext(), log) }
    single {
        val app = androidContext()
        val demo = get<DemoMode>().parts
        // Backed up and moved to a new phone (data_extraction_rules.xml); demo mode keeps the person's state out of its database.
        val userState = if (demo != null) {
            null
        } else {
            AtomicFile(File(app.filesDir, "user-state.json")).let { f ->
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
        }
        Store(indexDriver(app, demo?.dbName ?: Store.DB_NAME), log, userState = userState)
    }
    single<EmbedderFactory> {
        get<DemoMode>().parts?.factory
            ?: MediaPipeEmbedderFactory(androidContext(), get(ModelDir)) { app.retriever.DebugVision.backend(androidContext()) }
    }
    single<DeviceMonitor> { AndroidDeviceMonitor(androidContext()) }
    single<ContentHasher> { AndroidContentHasher(androidContext()) }
    single {
        val demo = get<DemoMode>().parts
        Retriever(
            store = get(),
            source = get(),
            extractor = get(),
            factory = get(),
            monitor = get(),
            hasher = get(),
            probe = get(),
            modelReady = demo?.modelReady ?: get<ModelDownload>()::ready,
            platformReady = get(PlatformReady),
            log = log,
            now = System::currentTimeMillis,
            zoneOffsetMs = { java.util.TimeZone.getDefault().getOffset(it).toLong() },
            dispatchers = CoreDispatchers(indexing = indexingDispatcher, readAhead = readAheadDispatcher),
            frames = AndroidFrameSource(androidContext()),
            lastExitCrashed = androidContext().let { app -> { ExitReasons.lastExitCrashed(app) } },
        )
    }

    single { IndexService(androidContext(), get()) }
    worker { IndexWorker(androidContext(), get(), get(), get(), get(PlatformReady), get(), get()) }
    single { StorageService(androidContext(), get(), get(), get(ModelDir)) }
    single { MediaDeleter(androidContext()) }
    singleOf(::LaunchQuery)
    singleOf(::RecentShortcuts)
    singleOf(::Calibrator)
    single { photoReader(androidContext()) }
    single { ModelRepository(androidContext(), get(), get(), get(AppScope), get()) }
    single {
        val app = androidContext()
        val calibrator = get<Calibrator>()
        UpgradeFlow(
            get(),
            get(),
            get(),
            get(),
            get(AppScope),
            onMeteredNetwork = { app.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered },
            calibrate = calibrator::tuneOnce,
        )
    }
    single {
        val app = androidContext()
        val retriever = get<Retriever>()
        val models = get<ModelRepository>()
        SetupFlow(
            retriever,
            get(),
            get(),
            get(AppScope),
            // Below Android 13 notifications need no permission, so there is nothing to ask.
            notificationsGranted = {
                Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            },
            // No mobile data question: Wi-Fi only unless the person allowed mobile data before, as Hello's note says.
            startDownload = { models.start(retriever.requiredBuild.value) },
        )
    }

    viewModelOf(::BrowseViewModel)
    viewModelOf(::StarHolder)
    viewModelOf(::FoldersViewModel)
    viewModelOf(::DuplicatesViewModel)
    viewModelOf(::StatusViewModel)
    viewModel {
        val calibrator = get<Calibrator>()
        SettingsViewModel(get(), get(), get(), get(), get(AppScope), get()) { calibrator.calibrate(tuneAgain = true) }
    }
    viewModelOf(::SearchViewModel)
    viewModelOf(::HomeViewModel)
    viewModelOf(::DocViewModel)
    viewModelOf(::PlayerViewModel)
}
