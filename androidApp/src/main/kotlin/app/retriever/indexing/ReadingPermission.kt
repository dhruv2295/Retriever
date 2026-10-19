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
import app.retriever.core.Retriever
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
    if (asking) {
        fun finish(enable: Boolean) {
            asking = false
            scope.launch {
                retriever.updatePrefs { it.copy(notificationsDismissed = true) }
                if (enable && Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else runPending()
            }
        }
        AlertDialog(
            onDismissRequest = { finish(false) },
            title = { Text("See reading progress outside Retriever?") },
            text = { Text("See progress and pause a reading session. We can also let you know when your sample is ready.") },
            confirmButton = { TextButton(onClick = { finish(true) }) { Text("Enable notifications") } },
            dismissButton = { TextButton(onClick = { finish(false) }) { Text("Not now") } },
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
