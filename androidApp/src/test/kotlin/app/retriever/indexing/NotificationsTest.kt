package app.retriever.indexing

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.retriever.core.CoreDispatchers
import app.retriever.core.DeviceWords
import app.retriever.core.Kind
import app.retriever.core.ReadingPhase
import app.retriever.core.ReadingPolicy
import app.retriever.core.ReadingProgress
import app.retriever.core.ReadingStatus
import app.retriever.core.StatusAction
import app.retriever.core.StopReason
import app.retriever.core.Words
import app.retriever.di.AppScope
import app.retriever.testFile
import app.retriever.testRetriever
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
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
        // The status line's bar, as on Home: silent, since the notification alerts only once.
        assertEquals(250, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(243, n.extras.getInt(Notification.EXTRA_PROGRESS))
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

    @Test fun anInterruptedRequestHasADismissibleResumeNoticeWithoutForegroundFlags() {
        Notifications.createChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val status = ReadingStatus(ReadingPhase.CHARGING, "Waiting", count = "12 of 20 files processed")
        Notifications.recovery(context, ReadingProgress(read = 12, scheduled = 8), status, StopReason.UNPLUGGED)
        val n = shadowOf(manager).getNotification(Notifications.WAITING_ID)
        assertNotNull(n)
        assertEquals(ReadingPolicy.stopCopy(StopReason.UNPLUGGED, DeviceWords.ANDROID), n.extras.getString(Notification.EXTRA_TITLE))
        assertFalse(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertFalse(n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
        assertTrue("below the floor only the charger starts it, so no Resume button", n.actions.isNullOrEmpty())
        assertEquals(Notifications.RESUME_PROCESSING, shadowOf(n.contentIntent).savedIntent.action)
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals(Words.READING_STATUS, n.publicVersion.extras.getString(Notification.EXTRA_TEXT))
        Notifications.recovery(context, ReadingProgress(read = 12, scheduled = 8), status, StopReason.PAUSED)
        val paused = shadowOf(manager).getNotification(Notifications.WAITING_ID)
        assertEquals(StatusAction.RESUME.label, paused.actions.single().title)
        assertEquals(Notifications.RESUME_PROCESSING, shadowOf(paused.actions.single().actionIntent).savedIntent.action)

        shadowOf(manager).setNotificationsEnabled(false)
        Notifications.recovery(context, ReadingProgress(scheduled = 8), status, null)
        assertNull(shadowOf(manager).getNotification(Notifications.WAITING_ID))
        // Building the mandatory foreground notification must remain possible when drawer access is denied.
        assertNotNull(Notifications.progress(context, ReadingProgress(), null))
    }

    /** A session's pause for the battery, heat or Battery Saver says what it waits for, and offers Keep paused as Status does. */
    @Test fun heldPausesExplainTheWaitAndOfferKeepPaused() {
        Notifications.createChannel(context)
        for ((phase, stop) in listOf(
            ReadingPhase.CHARGING to StopReason.UNPLUGGED,
            ReadingPhase.LOW_BATTERY to StopReason.LOW_BATTERY,
            ReadingPhase.CHARGING_UP to StopReason.CHARGING_UP,
            ReadingPhase.HOT to StopReason.HOT,
            ReadingPhase.POWER_SAVE to StopReason.POWER_SAVE,
        )) {
            val notice = Notifications.progress(
                context,
                ReadingProgress(read = 3, scheduled = 7),
                ReadingStatus(phase, "Paused", Words.strings.chargingUpDetail, StatusAction.RESUME, count = "3 of 10", done = 3, total = 10),
            )
            assertEquals(ReadingPolicy.stopCopy(stop, DeviceWords.ANDROID), notice.extras.getString(Notification.EXTRA_TITLE))
            val plugIn = stop == StopReason.UNPLUGGED || stop == StopReason.LOW_BATTERY
            assertEquals(stop.name, plugIn, notice.extras.getString(Notification.EXTRA_TEXT).orEmpty().contains(Words.strings.processingPlugInDetail))
            assertEquals(Words.strings.processingKeepPaused, notice.actions.single().title)
            assertEquals(PauseReceiver::class.java.name, shadowOf(notice.actions.single().actionIntent).savedIntent.component?.className)
            assertEquals(Notifications.OPEN_STATUS, shadowOf(notice.contentIntent).savedIntent.action)
            assertTrue(notice.flags and Notification.FLAG_ONGOING_EVENT != 0)
        }
    }

    @Test fun keepPausedNotificationActionPersistsPauseAndEndsTheExplicitSession() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val retriever = testRetriever(dispatchers = CoreDispatchers(io = Dispatchers.Unconfined))
        startKoin {
            modules(
                module {
                    single { IndexService(context, retriever) }
                    single(AppScope) { scope }
                },
            )
        }
        try {
            retriever.startProcessing(false)
            Notifications.createChannel(context)
            val notification = Notifications.progress(context, ReadingProgress(scheduled = 8), ReadingStatus(ReadingPhase.HOT, "Cooling down"))
            notification.actions.single().actionIntent.send()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(retriever.prefs.value.paused)
            assertFalse(retriever.prefs.value.started)
        } finally {
            stopKoin()
            scope.cancel()
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }
}
