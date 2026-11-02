package app.retriever

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.retriever.core.Retriever
import app.retriever.indexing.Notifications
import app.retriever.nav.AppNav
import app.retriever.search.LaunchQuery
import app.retriever.ui.RetrieverTheme
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    internal var statusRequest by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    private val launchQuery: LaunchQuery by inject()
    private val retriever: Retriever by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // AppNav draws nothing until setUp settles; the splash covers that wait.
        splash.setKeepOnScreenCondition { retriever.setUp.value == null }
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        // Only a fresh launch: a recreated activity (rotation, restore) still holds the intent it was opened with.
        if (savedInstanceState == null && intent.action == Notifications.OPEN_STATUS) statusRequest++
        Notifications.cancelSample(this)
        launchQuery.from(intent)
        setContent { RetrieverTheme { AppNav(statusRequest = statusRequest) } }
    }

    override fun onStart() {
        super.onStart()
        Notifications.cancelSample(this)
    }

    // Coming to the front counts as use, so an old touch can't make a just-opened app idle (Retriever.onScreen).
    override fun onResume() {
        super.onResume()
        retriever.inputSeen()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        retriever.inputSeen()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Notifications.OPEN_STATUS) statusRequest++
        Notifications.cancelSample(this)
        launchQuery.from(intent)
    }
}
