package app.retriever.core

import platform.Foundation.NSProcessInfo

internal actual val cpuCores: Int = NSProcessInfo.processInfo.activeProcessorCount.toInt()
