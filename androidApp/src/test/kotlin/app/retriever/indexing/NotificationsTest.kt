package app.retriever.indexing

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.retriever.core.ReadingPhase
import app.retriever.core.ReadingProgress
import app.retriever.core.ReadingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class NotificationsTest {
    @Test fun progressIsPrivateSilentAndUsesReadOutcomesWithStatusDestination() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Notifications.createChannel(context)
        val pause = PendingIntent.getBroadcast(context, 0, Intent(context, PauseReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notifications.progress(
            context,
            ReadingProgress(read = 222, partlyRead = 18, scheduled = 7, couldntRead = 3),
            ReadingStatus(
                ReadingPhase.READING,
                "Processing your files",
                "240 of 247 files done. You can close Retriever; it carries on. Search works the whole time.",
            ),
            pause,
        )
        // The status line's own words, as on Home.
        assertEquals("Processing your files", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("240 of 247 files done. You can close Retriever; it carries on. Search works the whole time.", n.extras.getString(Notification.EXTRA_TEXT))
        assertEquals("Pause", n.actions.single().title)
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        val channel = context.getSystemService(android.app.NotificationManager::class.java).getNotificationChannel(Notifications.CHANNEL)
        assertNull(channel.sound)
        assertEquals(false, channel.shouldVibrate())
        assertNotNull(n.publicVersion)
        assertEquals(Notifications.OPEN_STATUS, shadowOf(n.contentIntent).savedIntent.action)
        assertEquals(0, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
    }
}
