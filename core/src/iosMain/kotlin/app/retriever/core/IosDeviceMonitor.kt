package app.retriever.core

import platform.Foundation.NSProcessInfo
import platform.Foundation.NSProcessInfoThermalState
import platform.Foundation.lowPowerModeEnabled
import platform.Foundation.thermalState
import kotlin.concurrent.Volatile

/** Swift sets the battery fields and [active] on the main thread; thermal state and Low Power Mode are read here. */
class IosDeviceMonitor(private val ramGb: Double) : DeviceMonitor {
    @Volatile
    var charging: Boolean = false

    @Volatile
    var batteryPct: Int = 100

    /** The app is in the foreground. */
    @Volatile
    var active: Boolean = false

    /** The person asked to keep reading after they leave (the continued task), so Low Power Mode doesn't stop it. */
    @Volatile
    var continued: Boolean = false

    /** iOS granted that continued read the GPU in the background; IosEmbedderFactory drops it when the GPU refuses. */
    @Volatile
    var backgroundGpu: Boolean = false

    override fun state(): DeviceState {
        val info = NSProcessInfo.processInfo
        val thermal = when (info.thermalState) {
            NSProcessInfoThermalState.NSProcessInfoThermalStateNominal -> Thermal.NONE
            NSProcessInfoThermalState.NSProcessInfoThermalStateFair -> Thermal.LIGHT
            NSProcessInfoThermalState.NSProcessInfoThermalStateSerious -> Thermal.SEVERE
            else -> Thermal.CRITICAL
        }
        return DeviceState(
            ramGb = ramGb,
            // iOS reports plugged in (charging or full), never "level rising": the same flag answers both.
            charging = charging,
            pluggedIn = charging,
            batteryPct = batteryPct,
            thermal = thermal,
            // A continued session the person started carries on in Low Power Mode; the battery floors still apply.
            powerSave = info.lowPowerModeEnabled && !continued,
            // iOS can't tell whether the screen is on. With screenOn == visible, Retriever's busy() never trips
            // while the app is open, and the batch policy stays visibility-based.
            screenOn = active,
        )
    }
}
