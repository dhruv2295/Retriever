package app.retriever.indexing

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.retriever.core.Retriever
import org.koin.compose.koinInject

/**
 * A start tap asks Android's notification question once, in context, then starts the work whatever the answer:
 * processing runs either way, and the notification only decides whether it shows in the shade. Home's offer asks again.
 */
@Composable
fun rememberReadingPermission(retriever: Retriever = koinInject()): (() -> Unit) -> Unit {
    val context = LocalContext.current
    // Leaving this screen, including rotation, drops the pending action; only an answer on this screen can run it.
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    DisposableEffect(Unit) { onDispose { pending = null } }
    val requestNotifications = rememberNotificationPermission(retriever, openSettingsWhenBlocked = false) {
        val action = pending
        pending = null
        action?.invoke()
    }
    return { action ->
        if (Build.VERSION.SDK_INT < 33 || processingNotificationsEnabled(context) || retriever.prefs.value.notificationsPermissionRequested) {
            action()
        } else {
            NotificationPermissionSession.asked = true
            pending = action
            requestNotifications()
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
