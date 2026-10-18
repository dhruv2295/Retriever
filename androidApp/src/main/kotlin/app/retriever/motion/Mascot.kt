package app.retriever.motion

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import app.retriever.BuildConfig
import app.retriever.core.motion.MascotClip
import app.retriever.core.motion.MascotDirector
import app.retriever.core.motion.MascotMood
import app.retriever.core.motion.MascotStep
import app.retriever.core.motion.StepKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The golden retriever, animated. Give it a width (160.dp in most places, 260.dp on the welcome); its height follows
 * the art. Decorative: screens carry the state in text, so it has no content description.
 *
 * Frames are animated WebPs (assets, shared with iOS) played by the platform's [AnimatedImageDrawable], which decodes
 * off the main thread; [MascotDirector] picks each next clip. It shows a still pose instead when animations are off,
 * it is off screen, the app is not resumed, Battery Saver is on, or the phone is warm. [settles] false keeps the
 * walk going (setup); the default settles into a walk-and-rest cadence after two minutes (Home, Status).
 */
@Composable
fun Mascot(mood: MascotMood, modifier: Modifier = Modifier, settles: Boolean = true) {
    val context = LocalContext.current
    val frames = remember { MascotFrames(context) }
    val view = remember {
        ImageView(context).apply {
            adjustViewBounds = true
            setImageDrawable(frames.still(MascotDirector.still(mood)))
        }
    }
    var currentMood by remember { mutableStateOf(mood) }
    currentMood = mood
    var visible by remember { mutableStateOf(false) }
    val resumed = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.RESUMED)
    val appeared = remember { SystemClock.elapsedRealtime() }
    val history = remember { StepHistory() }

    LaunchedEffect(visible, resumed) {
        if (BuildConfig.DEBUG) Log.d("Mascot", "$currentMood: visible=$visible resumed=$resumed")
        val moods = snapshotFlow { currentMood }
        if (!visible || !resumed) {
            moods.collect { view.setImageDrawable(frames.still(MascotDirector.still(it))) }
        }
        while (true) {
            val now = currentMood
            if (!motionAllowed(context)) {
                view.setImageDrawable(frames.still(MascotDirector.still(now)))
                withTimeoutOrNull(RECHECK_MS) { moods.first { it != now } }
                continue
            }
            val step = MascotDirector.next(now, history.last, (SystemClock.elapsedRealtime() - appeared) / 1000.0, settles)
            if (BuildConfig.DEBUG) Log.d("Mascot", "$now: $step")
            when (step.kind) {
                StepKind.HOLD -> {
                    view.setImageDrawable(frames.still(step.clip))
                    val ms = step.seconds?.let { (it * 1000).toLong() } ?: Long.MAX_VALUE
                    withTimeoutOrNull(ms) { moods.first { it != now } }
                }

                StepKind.ONCE -> frames.anim(step.clip).playToEnd(view, repeat = 0)

                StepKind.LOOP -> {
                    val anim = frames.anim(step.clip)
                    // Ask half a loop early: the request lands inside the last wanted loop, which then finishes.
                    val end = SystemClock.elapsedRealtime() + ((step.seconds!! - step.clip.seconds / 2) * 1000).toLong()
                    anim.playToEnd(view, repeat = AnimatedImageDrawable.REPEAT_INFINITE) {
                        while (true) {
                            val left = end - SystemClock.elapsedRealtime()
                            if (left <= 0) break
                            if (withTimeoutOrNull(minOf(left, RECHECK_MS)) { moods.first { it != now } } != null) break
                            if (!motionAllowed(context)) break
                        }
                        // Finish the loop in progress, so the next clip starts from the pose this one ends on.
                        anim.repeatCount = 0
                    }
                }
            }
            // Only a finished step counts: one cut short (scrolled away, app left) plays again from its start.
            history.last = step
        }
    }

    AndroidView(
        factory = { view },
        modifier = modifier.onVisibilityChanged(minDurationMs = 100, minFractionVisible = 0.2f) { visible = it },
    )
}

private const val RECHECK_MS = 10_000L

private class StepHistory {
    var last: MascotStep? = null
}

/** Motion runs only with animations on, Battery Saver off, and the phone below moderate heat. */
internal fun motionAllowed(animatorsOn: Boolean, powerSave: Boolean, thermalStatus: Int): Boolean =
    animatorsOn && !powerSave && thermalStatus < PowerManager.THERMAL_STATUS_MODERATE

private fun motionAllowed(context: Context): Boolean {
    val power = context.getSystemService(PowerManager::class.java)
    return motionAllowed(ValueAnimator.areAnimatorsEnabled(), power.isPowerSaveMode, power.currentThermalStatus)
}

/** Starts the drawable with [repeat] and suspends until it ends; [whilePlaying] runs alongside (cancelled at the end). */
private suspend fun AnimatedImageDrawable.playToEnd(view: ImageView, repeat: Int, whilePlaying: suspend () -> Unit = {}) {
    repeatCount = repeat
    view.setImageDrawable(this)
    try {
        coroutineScope {
            val side = launch { whilePlaying() }
            suspendCancellableCoroutine { cont ->
                val callback = object : Animatable2.AnimationCallback() {
                    override fun onAnimationEnd(drawable: Drawable?) {
                        unregisterAnimationCallback(this)
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
                registerAnimationCallback(callback)
                cont.invokeOnCancellation { unregisterAnimationCallback(callback) }
                start()
            }
            side.cancel()
        }
    } finally {
        stop()
    }
}

/** Decodes each clip once per mascot, off the main thread. Stills are small single-frame WebPs. */
private class MascotFrames(private val context: Context) {
    private val cache = HashMap<String, Drawable>()

    fun still(clip: MascotClip): Drawable = cache.getOrPut(clip.still) { decode(clip.still) }

    suspend fun anim(clip: MascotClip): AnimatedImageDrawable =
        (cache[clip.file] ?: withContext(Dispatchers.IO) { decode(clip.file) }.also { cache[clip.file] = it }) as AnimatedImageDrawable

    private fun decode(file: String): Drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.assets, file))
}
