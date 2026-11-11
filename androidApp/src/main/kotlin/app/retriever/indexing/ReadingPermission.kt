package app.retriever.indexing

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retriever.core.Retriever
import app.retriever.core.Words
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.koin.compose.koinInject

/** Ask once on a scheduling tap; declining never blocks the action. */
@Composable
fun rememberReadingPermission(retriever: Retriever = koinInject(), onboarding: Boolean = false): (() -> Unit) -> Unit {
    val context = LocalContext.current
    // Leaving this screen, including rotation, drops the pending action; only an answer on this screen can run it.
    var asking by remember { mutableStateOf(false) }
    var allowing by remember { mutableStateOf<Boolean?>(null) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun runPending() {
        val action = pending
        pending = null
        action?.invoke()
    }
    DisposableEffect(Unit) { onDispose { pending = null } }
    val requestNotifications = rememberNotificationPermission(retriever, openSettingsWhenBlocked = !onboarding) { runPending() }
    LaunchedEffect(allowing) {
        val enable = allowing ?: return@LaunchedEffect
        retriever.updatePrefs { it.copy(notificationsDismissed = true) }
        allowing = null
        if (enable) requestNotifications() else runPending()
    }
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
            allowing = enable
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
            (retriever.prefs.value.notificationsDismissed && (!onboarding || NotificationPermissionSession.onboardingAsked))
        ) {
            action()
        } else {
            pending = action
            asking = true
        }
    }
}

/** Retriever's page in Android's settings, where Battery holds the Unrestricted choice. */
fun openBatterySettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    } catch (_: ActivityNotFoundException) {
    }
}
