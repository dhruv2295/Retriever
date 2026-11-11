package app.retriever.indexing

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.retriever.core.Retriever
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

// Kept for the process lifetime, so rotation and trips to system settings cannot make onboarding offer twice.
internal object NotificationPermissionSession {
    var onboardingAsked by mutableStateOf(false)
}

internal fun processingNotificationsEnabled(appEnabled: Boolean, channelImportance: Int?): Boolean =
    appEnabled && channelImportance != NotificationManager.IMPORTANCE_NONE

internal fun processingNotificationsEnabled(context: Context): Boolean {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    return processingNotificationsEnabled(manager.areNotificationsEnabled(), manager.getNotificationChannel(Notifications.CHANNEL)?.importance)
}

@Composable
fun rememberProcessingNotificationsEnabled(): State<Boolean> {
    val context = LocalContext.current
    val enabled = remember(context) { mutableStateOf(processingNotificationsEnabled(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { enabled.value = processingNotificationsEnabled(context) }
    return enabled
}

internal fun canRequestNotifications(sdk: Int, granted: Boolean, requested: Boolean, showRationale: Boolean): Boolean =
    sdk >= 33 && !granted && (!requested || showRationale)

@Composable
fun rememberNotificationPermission(retriever: Retriever = koinInject(), onResult: () -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val finished by rememberUpdatedState(onResult)
    // Unlike RequestPermission's Boolean, an empty result preserves a swiped-away prompt as unanswered.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = if (Build.VERSION.SDK_INT >= 33) result[Manifest.permission.POST_NOTIFICATIONS] else null
        scope.launch {
            if (granted != null) {
                val previouslyRequested = retriever.prefs.value.notificationsPermissionRequested
                retriever.updatePrefs { it.copy(notificationsPermissionRequested = true) }
                // Older installs have no request history; Android may have already stopped showing their prompt.
                if (!granted && !previouslyRequested && activity != null && Build.VERSION.SDK_INT >= 33 &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
                ) {
                    openNotificationSettings(context)
                }
            }
            finished()
        }
    }
    return remember(context, activity, retriever, launcher) {
        {
            if (activity != null && Build.VERSION.SDK_INT >= 33 && canRequestNotifications(
                    Build.VERSION.SDK_INT,
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                    retriever.prefs.value.notificationsPermissionRequested,
                    ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS),
                )
            ) {
                launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            } else {
                openNotificationSettings(context)
                finished()
            }
        }
    }
}

fun openNotificationSettings(context: Context) {
    val pages = listOf(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )
    for (page in pages) {
        try {
            context.startActivity(page)
            return
        } catch (_: ActivityNotFoundException) {
        }
    }
}
