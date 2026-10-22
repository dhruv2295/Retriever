package app.retriever.indexing

import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ChargingBoostTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val modeFile = File(context.filesDir, ChargingBoost.MODE_FILE)

    @After fun tearDown() {
        modeFile.delete()
    }

    @Test fun offUnlessTheModeFileSaysOtherwise() {
        val boost = ChargingBoost(context)
        assertEquals(ChargingBoost.Mode.OFF, boost.mode())
        modeFile.writeText("PRIORITY\n")
        assertEquals(ChargingBoost.Mode.PRIORITY, boost.mode())
        modeFile.writeText("nonsense")
        assertEquals(ChargingBoost.Mode.OFF, boost.mode())
    }

    @Test fun aBackgroundThreadRunsAtNormalPriorityOnTheChargerAndGoesBackOffIt() {
        modeFile.writeText("PRIORITY")
        val tid = Process.myTid()
        val boost = ChargingBoost(context) { listOf(tid) }
        Process.setThreadPriority(tid, Process.THREAD_PRIORITY_BACKGROUND)
        boost.apply(pluggedIn = true)
        assertEquals(Process.THREAD_PRIORITY_DEFAULT, Process.getThreadPriority(tid))
        boost.apply(pluggedIn = false)
        assertEquals(Process.THREAD_PRIORITY_BACKGROUND, Process.getThreadPriority(tid))
    }
}
