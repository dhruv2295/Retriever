package app.retriever.indexing

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
fun rememberReadingPermission(retriever: Retriever = koinInject(), onboarding: Boolean = false): (() -> Unit) -> Unit {
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
    val requestNotifications = rememberNotificationPermission(retriever) { runPending() }
    // Core words it from the demo's state; the dialog waits the one frame the answer takes, so its text never changes
    // while it is up.
    val asked by remember(asking) {
        val running = retriever.setup.value?.demoRunning == true
        if (asking) retriever.tasteProgress().map { Words.notificationsAsk(running, it) } else flowOf(null)
    }.collectAsStateWithLifecycle(null)
    val text = asked
    if (asking && text != null) {
        LaunchedEffect(Unit) { if (onboarding) NotificationPermissionSession.onboardingAsked = true }
        fun finish(enable: Boolean) {
            asking = false
            scope.launch {
                retriever.updatePrefs { it.copy(notificationsDismissed = true) }
                if (enable) requestNotifications() else runPending()
            }
        }
        AlertDialog(
            onDismissRequest = { finish(false) },
            title = { Text(Words.strings.notificationPermissionAsk) },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { finish(true) }) { Text(Words.ALLOW) } },
            dismissButton = { TextButton(onClick = { finish(false) }) { Text(Words.NOT_NOW) } },
        )
    }
    return { action ->
        if (Build.VERSION.SDK_INT < 33 || processingNotificationsEnabled(context) ||
            retriever.prefs.value.notificationsDismissed
        ) {
            action()
        } else {
            pending = action
            asking = true
        }
    }
}

/**
 * Asked as the full read starts, unless Retriever already runs unrestricted. Android keeps a background job on the two
 * slowest cores (cpuset background: cores 0-1 of 7 on a Pixel); only the reading service gets the fast ones, and it may
 * start on a charger without the app in front only once battery optimization is off.
 */
@Composable
fun rememberBatteryAsk(): () -> Unit {
    val context = LocalContext.current
    var asking by rememberSaveable { mutableStateOf(false) }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(Words.strings.processFasterAsk) },
            text = {
                Text(Words.strings.processFasterBody)
            },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    openBatterySettings(context)
                }) { Text(Words.strings.openSettingsLowercase) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(Words.NOT_NOW) } },
        )
    }
    return remember(context) {
        { if (!context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) asking = true }
    }
}

/** Retriever's page in Android's settings, where Battery holds the Unrestricted choice. */
fun openBatterySettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    } catch (_: ActivityNotFoundException) {
    }
}
