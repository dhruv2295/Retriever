package app.retriever.indexing

import android.app.NotificationManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionTest {
    @Test fun theSwitchNeedsAppAccessAndAnUnblockedChannel() {
        assertTrue(processingNotificationsEnabled(true, NotificationManager.IMPORTANCE_LOW))
        assertTrue(processingNotificationsEnabled(true, null))
        assertFalse(processingNotificationsEnabled(true, NotificationManager.IMPORTANCE_NONE))
        assertFalse(processingNotificationsEnabled(false, NotificationManager.IMPORTANCE_LOW))
        assertFalse(processingNotificationsEnabled(false, null))
    }

    @Test fun onlyNewOrRetryablePermissionsUseThePrompt() {
        assertTrue(canRequestNotifications(33, granted = false, requested = false, showRationale = false))
        assertTrue(canRequestNotifications(35, granted = false, requested = true, showRationale = true))
        assertFalse(canRequestNotifications(35, granted = false, requested = true, showRationale = false))
        assertFalse(canRequestNotifications(35, granted = true, requested = true, showRationale = false))
        assertFalse(canRequestNotifications(32, granted = false, requested = false, showRationale = false))
    }
}
