package app.retriever

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.retriever.core.Retriever
import app.retriever.nav.AppNav
import app.retriever.search.LaunchQuery
import app.retriever.ui.RetrieverTheme
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    private val launchQuery: LaunchQuery by inject()
    private val retriever: Retriever by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // AppNav draws nothing until setUp settles; the splash covers that wait.
        splash.setKeepOnScreenCondition { retriever.setUp.value == null }
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        launchQuery.from(intent)
        setContent { RetrieverTheme { AppNav() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        launchQuery.from(intent)
    }
}
