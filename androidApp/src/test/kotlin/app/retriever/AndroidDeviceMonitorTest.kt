package app.retriever

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import app.retriever.core.android.AndroidDeviceMonitor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A Pixel on AC at 84% reported isCharging false for minutes after plugging in: being on the charger is its own fact. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AndroidDeviceMonitorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun battery(plugged: Int) {
        shadowOf(context.getSystemService(BatteryManager::class.java)).setIsCharging(false)
        @Suppress("DEPRECATION") // The sticky battery broadcast is how a test puts the phone on a charger.
        context.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, plugged))
    }

    @Test fun onACWhileTheLevelIsNotRisingIsPluggedIn() {
        battery(BatteryManager.BATTERY_PLUGGED_AC)
        val state = AndroidDeviceMonitor(context).state()
        assertTrue(state.pluggedIn)
        assertFalse(state.charging)
    }

    @Test fun offTheChargerIsNotPluggedIn() {
        battery(0)
        assertFalse(AndroidDeviceMonitor(context).state().pluggedIn)
    }
}
