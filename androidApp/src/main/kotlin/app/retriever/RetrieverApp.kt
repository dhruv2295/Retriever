package app.retriever

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.StrictMode
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import app.retriever.access.Access
import app.retriever.access.AccessService
import app.retriever.core.Backend
import app.retriever.core.DeviceProfile
import app.retriever.core.Language
import app.retriever.core.ModelBuilds
import app.retriever.core.Retriever
import app.retriever.core.StopReason
import app.retriever.core.Store
import app.retriever.core.Tier
import app.retriever.core.Words
import app.retriever.di.AppScope
import app.retriever.di.DemoMode
import app.retriever.di.appModule
import app.retriever.indexing.IndexService
import app.retriever.indexing.Notifications
import app.retriever.model.UpgradeFlow
import app.retriever.setup.SetupFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.factory.KoinWorkerFactory
import org.koin.core.context.startKoin

class RetrieverApp :
    Application(),
    Configuration.Provider,
    DefaultLifecycleObserver {
    // WorkManager builds itself on first use, not at launch (its startup initializer is removed in the manifest).
    // Koin builds the workers, so IndexWorker gets its dependencies through its constructor.
    override val workManagerConfiguration: Configuration = Configuration.Builder().setWorkerFactory(KoinWorkerFactory()).build()

    private val retriever: Retriever by inject()
    private val scope: CoroutineScope by inject(AppScope)
    private val index: IndexService by inject()
    private var warmUp: Job? = null

    override fun onCreate() {
        super<Application>.onCreate()
        useResolvedLanguage()
        if (BuildConfig.DEBUG) strictMode()
        startKoin {
            androidContext(this@RetrieverApp)
            modules(appModule)
        }
        // First, so building the graph (disk reads) happens here on IO, not on the main thread.
        scope.launch {
            start()
            // ReadingService went with the last process, and its session with it.
            index.endStaleSession()
            // Before setup too: the worker's sync-only batch makes new files findable by name.
            index.watchMedia()
            if (libraryChosen()) {
                index.schedulePeriodic(retriever.prefs.value.onlyWhileCharging)
                index.indexNow()
            }
        }
        Notifications.createChannel(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        // While the process lives these wake the reader at once: WorkManager's charging constraint waits for the level to rise,
        // up to 15 minutes, and idle can take an hour.
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    index.deviceChanged()
                    if (retriever.uiVisible) scope.launch { if (libraryChosen()) index.recoverRequested() }
                }
            },
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED).apply { addAction(Intent.ACTION_POWER_CONNECTED) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** Access was given and confirmed in setup, so there is a library to read; waits for the store. */
    private suspend fun libraryChosen(): Boolean = retriever.setup.filterNotNull().first().withAccess(get<AccessService>().access().ready).libraryChosen

    /** What the app could see when it was last in front; Settings may have changed it since. */
    private var seen: Access? = null

    override fun onStart(owner: LifecycleOwner) {
        retriever.uiVisible = true
        val access = get<AccessService>()
        val now = access.access()
        if (seen != null && seen != now) access.changed()
        seen = now
        if (!now.ready) scope.launch { retriever.clearUnavailableLibrary() }
        // Warm independently of the trial; leaving cancels even a warm-up waiting for setup or the model lock.
        warmUp?.cancel()
        warmUp = scope.launch {
            // Names and words search with no model, so warming waits only for the store.
            retriever.setup.first { it != null }
            if (retriever.uiVisible) retriever.warmSearch()
        }
        // The setup is null until start() settles it, which a cold start's first onStart beats.
        // An unfinished sample, or a batch held back for the charger, a busy phone or Battery Saver, runs now, in front, if
        // the saved schedule allows it.
        scope.launch {
            if (resumable() && (retriever.hasPendingTaste() || (retriever.state.value.lastStop in HELD && retriever.readingBlocker() == null))) {
                index.indexNow()
            }
        }
        scope.launch {
            // Picks setup and the upgrade up where they stopped, whichever screen the user comes back to. Built once
            // the setup settles, so they seed from what is stored.
            retriever.setup.first { it != null }
            get<SetupFlow>().refresh()
            get<UpgradeFlow>().refresh()
        }
    }

    private suspend fun resumable() = libraryChosen() && retriever.uiVisible

    // A session's ReadingService carries on by itself after leaving; a job's batch goes on to its budget.
    override fun onStop(owner: LifecycleOwner) {
        warmUp?.cancel()
        retriever.uiVisible = false
        // A hidden app is never busy: a screen that set it and was left without clearing it can't hold batches back.
        retriever.setUiBusy(false)
        scope.launch { retriever.releaseIfIdle() }
    }

    private companion object {
        val HELD = setOf(StopReason.UNPLUGGED, StopReason.POWER_SAVE)
    }

    /** A language change keeps the process: core's sentences follow the language Android resolved the app's resources to. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        useResolvedLanguage()
        // Creating the channel again renames it in Settings.
        Notifications.createChannel(this)
    }

    private fun useResolvedLanguage() {
        Words.language = Language.of(getString(R.string.language))
    }

    /**
     * Cached in the background, the process is next in line to be killed: give back what search and reading rebuild on
     * demand (the model, the vector table), as the memory guide asks at TRIM_MEMORY_BACKGROUND and above. UI_HIDDEN needs
     * nothing here: [onStop] already releases an idle model.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) scope.launch { retriever.trimMemory() }
    }

    /** Once per process, on IO: opens the database and settles [Retriever.setUp]. */
    private suspend fun start() {
        if (get<DemoMode>().parts != null) {
            val store = get<Store>()
            if (store.profile() == null) {
                store.setProfile(DeviceProfile(Tier.BALANCED, ModelBuilds.variantFor(retriever.probe), Backend.CPU, 70, 5, false, false))
            }
        }
        retriever.refreshSetUp()
    }

    /** Debug only: logs disk and network work on the main thread, and leaked closeables, receivers and URI grants. */
    private fun strictMode() {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .detectContentUriWithoutPermission()
                .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) detectUnsafeIntentLaunch() }
                .penaltyLog()
                .build(),
        )
    }
}
