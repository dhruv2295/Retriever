package app.retriever.ui

import android.text.format.Formatter
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DownloadSizeTest {
    @Test fun modelUsesPlatformDownloadPrecisionAndIndexKeepsItsPrecision() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals(Formatter.formatShortFileSize(context, 484_600_000), Format.downloadSize(context, 484_600_000))
        assertEquals("484.6 MB", Format.bytes(484_600_000))
    }
}
