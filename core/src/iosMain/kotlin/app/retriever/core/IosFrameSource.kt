@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package app.retriever.core

import kotlinx.cinterop.ObjCAction
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.Foundation.NSSelectorFromString
import platform.QuartzCore.CADisplayLink
import platform.darwin.NSObject

internal class IosFrameSource : FrameSource {
    private var link: CADisplayLink? = null
    private var target: DisplayTarget? = null

    override fun start(onFrame: (Double) -> Unit) {
        target = DisplayTarget(onFrame)
        link = CADisplayLink.displayLinkWithTarget(target!!, NSSelectorFromString("frame:"))
            .also { it.addToRunLoop(NSRunLoop.mainRunLoop, NSRunLoopCommonModes) }
    }

    override fun stop() {
        link?.invalidate()
        link = null
        target = null
    }
}

private class DisplayTarget(private val onFrame: (Double) -> Unit) : NSObject() {
    @ObjCAction
    fun frame(link: CADisplayLink) {
        onFrame(link.timestamp * 1000)
    }
}
