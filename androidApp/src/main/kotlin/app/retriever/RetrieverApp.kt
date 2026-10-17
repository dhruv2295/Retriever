package app.retriever

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.StrictMode
import androidx.work.Configuration
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.retriever.core.Backend
import app.retriever.core.DeviceProfile
import app.retriever.core.ModelBuilds
import app.retriever.core.Retriever
import app.retriever.core.StopReason
import app.retriever.core.Store
import app.retriever.core.Tier
import app.retriever.data.AccessService
import app.retriever.di.AppScope
import app.retriever.di.DemoMode
import app.retriever.di.appModule
import kotlinx.coroutines.CoroutineScope
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class RetrieverApp : Application(), Configuration.Provider {
    // WorkManager builds itself on first use, not at launch (its startup initializer is removed in the manifest).
    override val workManagerConfiguration: Configuration = Configuration.Builder().build()

    private val retriever: Retriever by inject()
    private val scope: CoroutineScope by inject(AppScope)

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) strictMode()
        startKoin {
            androidContext(this@RetrieverApp)
            modules(appModule)
        }
        // First, so building the graph (disk reads) happens here on IO, not on the main thread.
        scope.launch {
            start()
            if (retriever.setUp.value == true) {
                IndexScheduler.schedulePeriodic(this@RetrieverApp, retriever.prefs.value.onlyWhileCharging)
            }
        }
        Notifications.createChannel(this)
        MediaObserver.register(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            /** What the app could see when it was last in front; Settings may have changed it since. */
            private var seen: Access? = null

            override fun onStart(owner: LifecycleOwner) {
                retriever.uiVisible = true
                val access = get<AccessService>()
                val now = access.access()
                if (seen != null && seen != now) access.changed()
                seen = now
                // setUp is null until start() settles it, which a cold start's first onStart beats.
                scope.launch {
                    if (retriever.setUp.first { it != null } != true) return@launch
                    // A batch held back for the charger or a busy phone runs while the user watches, on battery too.
                    val held = retriever.state.value.lastStop.let { it == StopReason.UNPLUGGED || it == StopReason.BUSY }
                    if (held) IndexScheduler.indexNow(this@RetrieverApp)
                    retriever.warmSearch()
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
                retriever.uiVisible = false
            }
        })
        // A batch that stopped because the phone was in use (BUSY) resumes once the screen goes off.
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (retriever.state.value.lastStop == StopReason.BUSY) IndexScheduler.continueLater(context, 10.seconds)
                }
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
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

    // In the background with no batch running, the model is the biggest thing a low-memory killer sees.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND && !retriever.uiVisible && !retriever.state.value.running) {
            scope.launch { retriever.release() }
        }
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
