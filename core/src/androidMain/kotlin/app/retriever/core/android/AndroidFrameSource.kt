package app.retriever.core.android

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Choreographer
import android.view.Display
import app.retriever.core.FrameSource

class AndroidFrameSource(context: Context) : FrameSource {
    private val displays = context.getSystemService(DisplayManager::class.java)
    override val refreshRateHz: Double get() = displays.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate?.toDouble() ?: Double.NaN
    private var callback: Choreographer.FrameCallback? = null

    override fun start(onFrame: (Double) -> Unit) {
        val choreographer = Choreographer.getInstance()
        callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                onFrame(frameTimeNanos / 1e6)
                choreographer.postFrameCallback(this)
            }
        }.also(choreographer::postFrameCallback)
    }

    override fun stop() {
        callback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        callback = null
    }
}
