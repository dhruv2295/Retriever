package app.retriever.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Main-thread display callbacks, only subscribed during Tune's existing GPU image calls. Times are milliseconds. */
interface FrameSource {
    fun start(onFrame: (Double) -> Unit)

    fun stop()
}

internal class FrameGaps {
    private var previous: Double? = null
    var maxGapMs = 0.0
        private set

    fun frame(ms: Double, visible: Boolean) {
        if (visible) previous?.let { maxGapMs = maxOf(maxGapMs, ms - it) }
        previous = ms.takeIf { visible }
    }
}

// 250 ms exceeds the measured 150 ms GPU-on p99 (paused: 53 ms), isolating the seconds-long rendering stalls.
internal fun stallsRendering(maxGapMs: Double): Boolean = maxGapMs > 250.0

internal class RenderingStalls(private val source: FrameSource, private val visible: () -> Boolean) {
    var maxGapMs = 0.0
        private set

    suspend fun <T> image(block: suspend () -> T): T {
        val gaps = FrameGaps()
        var next = CompletableDeferred<Unit>()
        try {
            withContext(Dispatchers.Main) {
                source.start {
                    gaps.frame(it, visible())
                    next.complete(Unit)
                }
                // A baseline before inference; a hidden display need not deliver callbacks.
                withTimeoutOrNull(1000) { next.await() }
            }
            return block()
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                // Include the first frame after inference: the GPU may have held this callback too.
                next = CompletableDeferred()
                if (visible()) withTimeoutOrNull(1000) { next.await() }
                source.stop()
                maxGapMs = maxOf(maxGapMs, gaps.maxGapMs)
            }
        }
    }
}

/** Text keeps its selected backend; only measured foreground vision stalls move the vision encoder. */
fun visionBackendFor(profile: DeviceProfile, visible: Boolean): Backend =
    if (profile.backend == Backend.GPU && profile.gpuVisionStalls && visible) Backend.CPU else profile.backend
