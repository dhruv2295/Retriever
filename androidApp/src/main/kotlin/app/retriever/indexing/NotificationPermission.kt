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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.retriever.core.Retriever
import org.koin.compose.koinInject

// Kept for the process lifetime, so rotation and trips to system settings cannot make onboarding offer twice.
internal object NotificationPermissionSession {
    var onboardingAsked by mutableStateOf(false)
}

internal fun processingNotificationsEnabled(context: Context): Boolean {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    return manager.areNotificationsEnabled() && manager.getNotificationChannel(Notifications.CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
}

@Composable
fun rememberProcessingNotificationsEnabled(): State<Boolean> {
    val context = LocalContext.current
    val enabled = remember(context) { mutableStateOf(processingNotificationsEnabled(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { enabled.value = processingNotificationsEnabled(context) }
    return enabled
}

@Composable
fun rememberNotificationPermission(retriever: Retriever = koinInject(), openSettingsWhenBlocked: Boolean = true, onResult: () -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val finished by rememberUpdatedState(onResult)
    // Keep an answered prompt through rotation while its preference write is suspended; 2 means swiped away.
    var response by rememberSaveable { mutableStateOf<Int?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = if (Build.VERSION.SDK_INT >= 33) result[Manifest.permission.POST_NOTIFICATIONS] else null
        response = when (granted) {
            true -> 1
            false -> 0
            null -> 2
        }
    }
    LaunchedEffect(response) {
        val answer = response ?: return@LaunchedEffect
        if (answer != 2) {
            val previouslyRequested = retriever.prefs.value.notificationsPermissionRequested
            retriever.updatePrefs { it.copy(notificationsPermissionRequested = true) }
            // Older installs have no request history; Android may have already stopped showing their prompt.
            if (openSettingsWhenBlocked && answer == 0 && !previouslyRequested && activity != null && Build.VERSION.SDK_INT >= 33 &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
            ) {
                openNotificationSettings(context)
            }
        }
        response = null
        finished()
    }
    return remember(context, activity, retriever, launcher, openSettingsWhenBlocked) {
        {
            if (activity != null && Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                (
                    !retriever.prefs.value.notificationsPermissionRequested ||
                        ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
                    )
            ) {
                launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            } else {
                if (openSettingsWhenBlocked) openNotificationSettings(context)
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
