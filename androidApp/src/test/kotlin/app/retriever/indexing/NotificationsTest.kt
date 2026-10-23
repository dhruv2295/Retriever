package app.retriever.indexing

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.retriever.core.Kind
import app.retriever.core.ReadingPhase
import app.retriever.core.ReadingProgress
import app.retriever.core.ReadingStatus
import app.retriever.core.Words
import app.retriever.testFile
import app.retriever.testRetriever
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.minutes

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
                "You can close Retriever; it carries on. Search works the whole time.",
                count = "243 of 250 files processed",
                done = 243,
                total = 250,
            ),
            pause,
        )
        // The status line's own words, as on Home.
        assertEquals("Processing your files", n.extras.getString(Notification.EXTRA_TITLE))
        // The count left the detail, so the notification puts it back in front.
        val text = "243 of 250 files processed. You can close Retriever; it carries on. Search works the whole time."
        assertEquals(text, n.extras.getString(Notification.EXTRA_TEXT))
        assertEquals("Pause", n.actions.single().title)
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        val channel = context.getSystemService(android.app.NotificationManager::class.java).getNotificationChannel(Notifications.CHANNEL)
        assertNull(channel.sound)
        assertEquals(false, channel.shouldVibrate())
        assertNotNull(n.publicVersion)
        assertEquals(Notifications.OPEN_STATUS, shadowOf(n.contentIntent).savedIntent.action)
        assertEquals(0, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
    }

    /** A words-only phone with every file processed after setup; [asked]: Home card's Notify me. */
    private fun caughtUp(asked: Boolean): IndexService {
        val r = testRetriever(listOf(testFile("notes.txt", Kind.DOC), testFile("lease.txt", Kind.DOC)), ramGb = 3.0, extract = { emptyList() })
        runBlocking {
            r.refreshSetUp()
            r.finishSetup()
            r.indexBatch(1.minutes)
            if (asked) r.requestCaughtUpNotice()
        }
        return IndexService(context, r)
    }

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun posted() = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
        .filter { it.extras.getString(Notification.EXTRA_TITLE) == Words.CAUGHT_UP_NOTICE_TITLE }

    @Test fun theCaughtUpNoticeComesOnceWhenAsked() {
        Notifications.createChannel(context)
        val index = caughtUp(asked = true)
        runBlocking {
            index.notifyCaughtUpIfReady()
            index.notifyCaughtUpIfReady()
        }
        val n = posted().single()
        assertEquals(Words.caughtUpNotice(2, textOnly = true), n.extras.getString(Notification.EXTRA_TEXT))
        assertNotNull(n.contentIntent)
    }

    @Test fun noCaughtUpNoticeWhenNotAsked() {
        Notifications.createChannel(context)
        runBlocking { caughtUp(asked = false).notifyCaughtUpIfReady() }
        assertEquals(0, posted().size)
    }
}
