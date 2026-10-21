/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/common/HardwareUtils.kt (Apache-2.0).
// Modified for Retriever: the Tensor codename sets identify NPU builds; SoC fields are guarded by SDK_INT.
package app.retriever.core.android

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import app.retriever.core.DeviceMonitor
import app.retriever.core.DeviceProbe
import app.retriever.core.DeviceState
import app.retriever.core.ModelBuilds
import app.retriever.core.Platform
import app.retriever.core.Thermal
import app.retriever.core.nominalRamGb
import java.io.File
import java.util.Locale

private fun memoryInfo(context: Context): ActivityManager.MemoryInfo =
    ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }

/** Advertised RAM where the platform reports it (API 34), else the OS-visible total. */
private fun memoryBytes(context: Context): Long = memoryInfo(context).let {
    if (Build.VERSION.SDK_INT >= 34) it.advertisedMem else it.totalMem
}

class AndroidDeviceMonitor(private val context: Context) : DeviceMonitor {
    private val batteryManager = context.getSystemService(BatteryManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val ramGb by lazy { nominalRamGb(memoryBytes(context)) }

    override fun state(): DeviceState = DeviceState(
        ramGb = ramGb,
        charging = batteryManager.isCharging,
        batteryPct = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
        thermal = thermal(powerManager.currentThermalStatus),
        screenOn = powerManager.isInteractive,
        powerSave = powerManager.isPowerSaveMode,
        // A null receiver reads the sticky broadcast without registering.
        pluggedIn = (context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
    )

    private fun thermal(status: Int): Thermal = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> Thermal.NONE
        PowerManager.THERMAL_STATUS_LIGHT -> Thermal.LIGHT
        PowerManager.THERMAL_STATUS_MODERATE -> Thermal.MODERATE
        PowerManager.THERMAL_STATUS_SEVERE -> Thermal.SEVERE
        else -> Thermal.CRITICAL
    }
}

class AndroidDeviceProbe(private val context: Context) {
    fun probe(): DeviceProbe {
        val sdk = Build.VERSION.SDK_INT
        val manufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else ""
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else ""
        return DeviceProbe(
            platform = Platform.ANDROID,
            sdkInt = sdk,
            socManufacturer = manufacturer,
            socModel = tensorName(socModel) ?: socModel,
            ramGb = nominalRamGb(memoryBytes(context)),
            oldMaliGpu = false,
            // ponytail: file presence, not a dlopen; a wrong yes costs one download and K2's fallback recovers
            npuDispatch = ModelBuilds.npuRuntimeReady(manufacturer) {
                File(context.applicationInfo.nativeLibraryDir, it).exists()
            },
        )
    }

    private fun tensorName(socModel: String): String? {
        if (socModel.contains("tensor", ignoreCase = true)) return null
        val codenames = listOf(Build.BOARD, Build.HARDWARE, Build.DEVICE).map { it.lowercase(Locale.ROOT) }
        return when {
            codenames.any { it in TENSOR_G6 } -> "Tensor G6"
            codenames.any { it in TENSOR_G5 } -> "Tensor G5"
            else -> null
        }
    }

    private companion object {
        val TENSOR_G5 = setOf("frankel", "blazer", "mustang", "rango", "laguna")
        val TENSOR_G6 = setOf("malibu")
    }
}
