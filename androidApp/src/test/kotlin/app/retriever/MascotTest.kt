package app.retriever

import android.os.PowerManager
import app.retriever.core.motion.MascotClip
import app.retriever.motion.motionAllowed
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MascotTest {
    private val frames = File("../iosApp/Retriever/Motion/Frames")

    @Test
    fun everyClipAndStillShipsAsWebp() {
        for (clip in MascotClip.entries) for (name in listOf(clip.file, clip.still)) {
            val bytes = File(frames, name).readBytes()
            assertEquals("$name is RIFF", "RIFF", String(bytes, 0, 4))
            assertEquals("$name is WEBP", "WEBP", String(bytes, 8, 4))
        }
    }

    @Test
    fun clipLengthsMatchTheirFrames() {
        // A WebP frame is an ANMF chunk; each clip's seconds (the director's timing) must equal frames x frame delay.
        for (clip in MascotClip.entries) {
            val bytes = File(frames, clip.file).readBytes()
            var ms = 0
            var i = 12
            while (i + 8 <= bytes.size) {
                val size = (bytes[i + 4].toInt() and 0xff) or ((bytes[i + 5].toInt() and 0xff) shl 8) or
                    ((bytes[i + 6].toInt() and 0xff) shl 16) or ((bytes[i + 7].toInt() and 0xff) shl 24)
                if (String(bytes, i, 4) == "ANMF") {
                    val d = i + 8 + 12
                    ms += (bytes[d].toInt() and 0xff) or ((bytes[d + 1].toInt() and 0xff) shl 8) or ((bytes[d + 2].toInt() and 0xff) shl 16)
                }
                i += 8 + size + (size and 1)
            }
            assertEquals("${clip.name} length", clip.seconds, ms / 1000.0, 0.03)
        }
    }

    @Test
    fun motionStopsForRemoveAnimationsBatterySaverAndHeat() {
        assertTrue(motionAllowed(animatorsOn = true, powerSave = false, thermalStatus = PowerManager.THERMAL_STATUS_LIGHT))
        assertFalse(motionAllowed(animatorsOn = false, powerSave = false, thermalStatus = PowerManager.THERMAL_STATUS_NONE))
        assertFalse(motionAllowed(animatorsOn = true, powerSave = true, thermalStatus = PowerManager.THERMAL_STATUS_NONE))
        assertFalse(motionAllowed(animatorsOn = true, powerSave = false, thermalStatus = PowerManager.THERMAL_STATUS_MODERATE))
    }
}
