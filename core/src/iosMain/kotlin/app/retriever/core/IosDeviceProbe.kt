package app.retriever.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.Foundation.NSProcessInfo
import platform.posix.uname
import platform.posix.utsname

/** iOS has no NPU dispatch for litertlm; [ModelBuilds.variantFor] maps it to M440, which has no audio. */
@OptIn(ExperimentalForeignApi::class)
fun iosDeviceProbe(): DeviceProbe = DeviceProbe(
    platform = Platform.IOS,
    sdkInt = 0,
    socManufacturer = "Apple",
    // The hardware model, e.g. "iPhone17,1"; "arm64" on the simulator.
    socModel = memScoped {
        val u = alloc<utsname>()
        uname(u.ptr)
        u.machine.toKString()
    },
    ramGb = nominalRamGb(NSProcessInfo.processInfo.physicalMemory.toLong()),
    oldMaliGpu = false,
    npuDispatch = false,
)
