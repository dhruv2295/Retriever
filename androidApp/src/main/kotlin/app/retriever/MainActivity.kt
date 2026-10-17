package app.retriever

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // AppNav draws nothing until setUp settles; the splash covers that wait.
        val graph = (application as RetrieverApp).graph
        splash.setKeepOnScreenCondition { graph.retriever.setUp.value == null }
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        LaunchQuery.from(intent)
        setContent { RetrieverTheme { AppNav() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        LaunchQuery.from(intent)
    }
}
