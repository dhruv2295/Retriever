package app.retriever.indexing

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retriever.core.Retriever
import app.retriever.core.Words
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Ask once on a scheduling tap; declining never blocks the action. */
@Composable
fun rememberReadingPermission(retriever: Retriever = koinInject()): (() -> Unit) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Saved, so the question survives a rotation. The tap's action can't be saved: a recreation runs it rather than drop it.
    var asking by rememberSaveable { mutableStateOf(false) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun runPending() {
        pending?.invoke()
        pending = null
    }
    DisposableEffect(Unit) { onDispose { runPending() } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { runPending() }
    // Core words it from the sample's state; the dialog waits the one frame the answer takes, so its text never changes
    // while it is up.
    val asked by remember(asking) {
        if (asking) retriever.tasteProgress().map { Words.notificationsAsk(retriever.setup.value?.decided == true, it) } else flowOf(null)
    }.collectAsStateWithLifecycle(null)
    val text = asked
    if (asking && text != null) {
        fun finish(enable: Boolean) {
            asking = false
            scope.launch {
                retriever.updatePrefs { it.copy(notificationsDismissed = true) }
                if (enable && Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else runPending()
            }
        }
        AlertDialog(
            onDismissRequest = { finish(false) },
            title = { Text("Show a notification while Retriever processes your files?") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { finish(true) }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { finish(false) }) { Text(Words.NOT_NOW) } },
        )
    }
    return { action ->
        if (Build.VERSION.SDK_INT < 33 || context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() ||
            retriever.prefs.value.notificationsDismissed
        ) {
            action()
        } else {
            pending = action
            asking = true
        }
    }
}
