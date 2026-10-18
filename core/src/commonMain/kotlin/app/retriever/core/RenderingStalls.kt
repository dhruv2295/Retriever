package app.retriever.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Main-thread display callbacks, only subscribed during Tune's existing GPU image calls. Times are milliseconds. */
interface FrameSource {
    val refreshRateHz: Double

    fun start(onFrame: (Double) -> Unit)

    fun stop()
}

internal class FrameGaps {
    private var previous: Double? = null
    var maxGapMs = 0.0
        private set

    var stalled = false
        private set

    fun frame(ms: Double, visible: Boolean, refreshRateHz: Double) {
        if (visible) {
            previous?.let {
                maxGapMs = maxOf(maxGapMs, ms - it)
                stalled = stalled || stallsRendering(ms - it, refreshRateHz)
            }
        }
        previous = ms.takeIf { visible }
    }
}

// Three display intervals: the same visible interruption budget at every refresh rate.
internal fun stallsRendering(maxGapMs: Double, refreshRateHz: Double): Boolean =
    refreshRateHz.isFinite() && refreshRateHz > 0 && maxGapMs > 3000.0 / refreshRateHz

internal class RenderingStalls(private val source: FrameSource, private val visible: () -> Boolean) {
    var maxGapMs = 0.0
        private set

    var stalled = false
        private set

    suspend fun <T> image(block: suspend () -> T): T {
        val gaps = FrameGaps()
        var next = CompletableDeferred<Unit>()
        try {
            withContext(Dispatchers.Main) {
                source.start {
                    gaps.frame(it, visible(), source.refreshRateHz)
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
                stalled = stalled || gaps.stalled
            }
        }
    }
}

/** Text keeps its selected backend; only measured foreground vision stalls move the vision encoder. */
fun visionBackendFor(profile: DeviceProfile, visible: Boolean): Backend =
    if (profile.backend == Backend.GPU && profile.gpuVisionStalls && visible) Backend.CPU else profile.backend
