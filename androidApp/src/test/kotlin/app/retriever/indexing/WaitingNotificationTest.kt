package app.retriever.indexing

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.retriever.core.DeviceState
import app.retriever.core.Kind
import app.retriever.core.ReadingSchedule
import app.retriever.core.Thermal
import app.retriever.testFile
import app.retriever.testRetriever
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.minutes

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WaitingNotificationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager get() = shadowOf(context.getSystemService(NotificationManager::class.java))

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        Notifications.createChannel(context)
    }

    @After fun tearDown() = WorkManagerTestInitHelper.closeWorkDatabase()

    @Test fun anAutomaticWaitDoesNotCreateAnUnrequestedProcessingNotice() = runBlocking {
        val retriever = testRetriever(
            files = listOf(testFile("new.txt", Kind.DOC)),
            ramGb = 3.0,
            deviceState = { DeviceState(3.0, false, 74, Thermal.NONE) },
        )
        retriever.refreshSetUp()
        retriever.finishSetup()
        retriever.setReadingSchedule(ReadingSchedule.MANUAL)
        retriever.indexBatch(1.minutes)
        val index = IndexService(context, retriever)
        index.updateWaitingNotification()
        assertNull(manager.getNotification(Notifications.WAITING_ID))
        retriever.requestProcessing(false)
        index.updateWaitingNotification()
        assertNotNull(manager.getNotification(Notifications.WAITING_ID))
        // Swiped away: the next automatic batch must not bring it back for the same request.
        Notifications.cancelWaiting(context)
        index.updateWaitingNotification()
        assertNull(manager.getNotification(Notifications.WAITING_ID))
        index.pause()
        assertNull(manager.getNotification(Notifications.WAITING_ID))
    }
}
