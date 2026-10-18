package app.retriever.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LogSinkTest {
    private val secret = "/storage/emulated/0/Documents/Tax Return 2025.pdf"

    @Test fun releaseWritesNoExceptionMessageAndNoThrowable() {
        ShadowLog.clear()
        LogSink.write(debug = false, "Couldn't index PDF", java.io.IOException("cannot open $secret"))

        val line = ShadowLog.getLogs().single()
        assertFalse(line.msg, "Tax Return" in line.msg || "cannot open" in line.msg)
        assertTrue(line.msg, "IOException" in line.msg && line.msg.startsWith("Couldn't index PDF"))
        assertNull("the throwable would print its message", line.throwable)
    }

    @Test fun debugKeepsTheThrowable() {
        ShadowLog.clear()
        val e = java.io.IOException(secret)
        LogSink.write(debug = true, "x", e)
        assertEquals(e, ShadowLog.getLogs().single().throwable)
    }
}
