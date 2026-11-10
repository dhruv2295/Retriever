package app.retriever

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.retriever.core.Retriever
import app.retriever.core.Words
import app.retriever.indexing.Notifications
import app.retriever.nav.AppNav
import app.retriever.search.RecentShortcuts
import app.retriever.ui.RetrieverTheme
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    internal var statusRequest by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    /** A search handed in from outside (a shortcut, `am start --es query`), or Ctrl+F's empty one; the counter lets the same words run again. */
    internal var searchRequest by androidx.compose.runtime.mutableStateOf<Pair<Int, String>?>(null)
        private set
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
        if (savedInstanceState == null) take(intent)
        setContent { RetrieverTheme { AppNav(statusRequest = statusRequest, searchRequest = searchRequest) } }
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
        take(intent)
    }

    // Ctrl+F opens search from any screen, as on a desktop or with a keyboard case; Meta+/ lists it with the system's own.
    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_F || !event.isCtrlPressed) return super.onKeyShortcut(keyCode, event)
        searchRequest = (searchRequest?.first ?: 0) + 1 to ""
        return true
    }

    // Esc goes back, as in a desktop window, when nothing on screen took it (an open menu closes itself first). Only
    // within the app: on Home it does nothing, rather than closing the app as Back would.
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_ESCAPE || !onBackPressedDispatcher.hasEnabledCallbacks()) return super.onKeyUp(keyCode, event)
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        data += KeyboardShortcutGroup(
            "Retriever",
            listOf(
                KeyboardShortcutInfo(Words.strings.searchAction, KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON),
                KeyboardShortcutInfo(Words.strings.back, KeyEvent.KEYCODE_ESCAPE, 0),
            ),
        )
    }

    private fun take(intent: Intent) {
        intent.getStringExtra(RecentShortcuts.EXTRA)?.trim()?.takeIf { it.isNotEmpty() }?.let { searchRequest = (searchRequest?.first ?: 0) + 1 to it }
    }
}
