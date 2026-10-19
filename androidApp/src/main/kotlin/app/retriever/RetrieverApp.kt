package app.retriever

import android.app.Application
import android.content.BroadcastReceiver
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
import app.retriever.core.ModelBuilds
import app.retriever.core.Retriever
import app.retriever.core.StopReason
import app.retriever.core.Store
import app.retriever.core.Tier
import app.retriever.di.AppScope
import app.retriever.di.DemoMode
import app.retriever.di.appModule
import app.retriever.indexing.IndexService
import app.retriever.indexing.Notifications
import app.retriever.setup.SetupFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
        if (BuildConfig.DEBUG) strictMode()
        startKoin {
            androidContext(this@RetrieverApp)
            modules(appModule)
        }
        // First, so building the graph (disk reads) happens here on IO, not on the main thread.
        scope.launch {
            start()
            // Before setup too: the worker's sync-only batch makes new files findable by name.
            index.watchMedia()
            if (retriever.setUp.value == true) {
                index.schedulePeriodic(retriever.prefs.value.onlyWhileCharging)
            }
        }
        Notifications.createChannel(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        // Screen-off hands a direct trial to durable work, or resumes a batch held back by another app.
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    scope.launch {
                        if (intent.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) index.powerSaveChanged() else index.screenOff()
                    }
                }
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

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
            if (retriever.setUp.first { it != null } == true && retriever.uiVisible) retriever.warmSearch()
        }
        // setUp is null until start() settles it, which a cold start's first onStart beats.
        scope.launch {
            if (retriever.setUp.first { it != null } != true || !retriever.uiVisible) return@launch
            // A batch held back for the charger or a busy phone runs while the user watches, on battery too.
            val held = retriever.state.value.lastStop.let { it == StopReason.UNPLUGGED || it == StopReason.BUSY || it == StopReason.POWER_SAVE }
            if (!index.readTrial() && held) index.indexNow()
        }
        scope.launch {
            // Picks the setup chain up where it stopped, whichever screen the user comes back to. Built once
            // setUp settles, so it seeds from the stored prefs.
            retriever.setUp.first { it != null }
            val setup = get<SetupFlow>()
            if (setup.ui.value.started) setup.refresh()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        warmUp?.cancel()
        retriever.uiVisible = false
        // A hidden app is never busy: a screen that set it and was left without clearing it can't hold batches back.
        retriever.setUiBusy(false)
        scope.launch {
            if (!retriever.uiVisible) index.screenOff()
            retriever.releaseIfIdle()
        }
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
