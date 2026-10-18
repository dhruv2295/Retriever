package app.retriever.core.android

import android.view.Choreographer
import app.retriever.core.FrameSource

class AndroidFrameSource : FrameSource {
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
