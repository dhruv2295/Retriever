package app.retriever

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import app.retriever.core.Backend
import app.retriever.core.FileState
import app.retriever.core.IndexState
import app.retriever.core.Kind
import app.retriever.core.ModelBuilds
import app.retriever.core.Prefs
import app.retriever.core.StopReason
import app.retriever.core.Store
import app.retriever.core.SourceFile
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders Setup and Status states to androidApp/build/shots for a visual check without a device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SetupStatusShotsTest {
    @get:Rule val rule = createComposeRule()

    private val build = ModelBuilds.npu.first().variant.generic
    private val going = SetupUi(started = true, files = true, namesSynced = true, build = build)

    private fun shot(name: String, dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        tallWindowForBigText(fontScale)
        rule.setContent { ShotTheme(dark, fontScale, content) }
        rule.shot(name)
    }

    private fun setup(name: String, ui: SetupUi, dark: Boolean = false, fontScale: Float = 1f) =
        shot(name, dark, fontScale) { SetupContent(ui) }

    @Test fun setupFresh() = setup("setup_fresh_light", SetupUi(build = build))
    @Test fun setupFreshDark() = setup("setup_fresh_dark", SetupUi(build = build), dark = true)
    @Test fun setupDownloading() =
        setup("setup_downloading_light", going.copy(model = ModelState.Downloading(0.45f, false), notificationAsk = true))
    @Test fun setupDownloadingDark() =
        setup("setup_downloading_dark", going.copy(model = ModelState.Downloading(0.45f, false)), dark = true)
    @Test fun setupDownloadingBigText() =
        setup("setup_downloading_text1_5", going.copy(model = ModelState.Downloading(0.45f, false)), fontScale = 1.5f)
    @Test fun setupWaitingForWifi() = setup("setup_wifi_light", going.copy(model = ModelState.WaitingForWifi))
    @Test fun setupTuning() = setup("setup_tuning_dark", going.copy(model = ModelState.Done, tune = TuneState.Running), dark = true)
    @Test fun setupFailed() =
        setup("setup_failed_light", going.copy(model = ModelState.Failed(spaceMessage(build))))
    @Test fun setupFilesDenied() =
        shot("setup_files_denied_light") { SetupContent(SetupUi(started = true, build = build), filesDenied = true) }
    @Test fun setupFellBack() =
        setup("setup_fellback_dark", going.copy(fellBack = true, model = ModelState.Downloading(0.1f, false)), dark = true)

    private val prefs = Prefs(excludedFolders = setOf("Android/media/WhatsApp/Stickers"))
    private val reading = Store.Counts(
        mapOf(FileState.INDEXED to 3_412L, FileState.PENDING to 8_118L, FileState.SKIPPED to 3L),
        mapOf(Kind.PHOTO to 9_000L, Kind.PDF to 2_533L),
        tailChunks = 12,
    )
    private val running = IndexState(
        running = true,
        processed = 120,
        pendingAtStart = 8_238,
        current = "IMG_20250914_183022.jpg",
        etaMinutes = 95,
        backend = Backend.GPU,
        detail = "the vision encoder runs on the processor",
    )
    private val skippedFiles = listOf("Scan 3.pdf" to "Download", "Old notes.docx" to "Documents", "IMG_0001.heic" to "DCIM/Camera")
        .mapIndexed { i, (name, folder) ->
            Store.StoredFile(i.toLong(), SourceFile("demo:$i", name, folder, "application/octet-stream", Kind.OTHER, 1_000, 0), 3)
        }

    private fun status(
        name: String,
        counts: Store.Counts = reading,
        index: IndexState = running,
        open: Boolean = false,
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) = shot(name, dark, fontScale) {
        StatusContent(counts, prefs, index, TuneState.Done("Using the graphics chip · about 0.8 s per photo"), skippedFiles, open, batteryHint = true)
    }

    @Test fun statusReading() = status("status_reading_light")
    @Test fun statusReadingDark() = status("status_reading_dark", dark = true)
    @Test fun statusReadingBigText() = status("status_reading_text1_5", fontScale = 1.5f)
    @Test fun statusSkippedOpen() = status("status_skipped_open_light", index = running.copy(running = false, lastStop = StopReason.UNPLUGGED), open = true)
    @Test fun statusCaughtUp() = status(
        "status_caught_up_dark",
        counts = Store.Counts(mapOf(FileState.INDEXED to 11_530L), mapOf(Kind.PHOTO to 9_000L), 0),
        index = IndexState(lastStop = StopReason.DONE, backend = Backend.GPU),
        dark = true,
    )
}
