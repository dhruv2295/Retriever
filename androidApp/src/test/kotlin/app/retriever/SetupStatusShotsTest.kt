package app.retriever

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.retriever.core.Backend
import app.retriever.core.FileState
import app.retriever.core.IndexState
import app.retriever.core.Kind
import app.retriever.core.ModelBuilds
import app.retriever.core.Prefs
import app.retriever.core.StopReason
import app.retriever.core.Store
import app.retriever.core.SourceFile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders Setup and Status states to androidApp/build/shots for a visual check without a device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SetupStatusShotsTest {
    @get:Rule val rule = createComposeRule()

    private val build = ModelBuilds.npu.first().variant.generic
    private val going = SetupUi(started = true, access = Access.ALL, namesSynced = true, build = build)

    /** [tall] fits a whole Status page, so the sections below the fold are in the shot too. */
    private fun shot(name: String, dark: Boolean = false, fontScale: Float = 1f, tall: Boolean = false, content: @Composable () -> Unit) {
        tallWindowForBigText(fontScale)
        if (tall) RuntimeEnvironment.setQualifiers("+h1500dp")
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

    private val choosing = SetupUi(started = true, build = build)
    @Test fun setupAccessChoice() = setup("setup_access_choice_light", choosing)
    @Test fun setupAccessChoiceDark() = setup("setup_access_choice_dark", choosing, dark = true)

    private val lowMemory = going.copy(access = Access(false, Photos.ALL, videos = true), textOnly = true, ramGb = 3.0)
    @Test fun setupTextOnly() = setup("setup_text_only_light", lowMemory)
    @Test fun setupTextOnlyDark() = setup("setup_text_only_dark", lowMemory, dark = true)

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
        access: Access = Access.ALL,
        textOnly: Boolean = false,
        tall: Boolean = false,
    ) = shot(name, dark, fontScale, tall) {
        StatusContent(
            counts, prefs, index, skippedFiles, open, batteryHint = true,
            actions = StatusActions(openSettings = {}), access = access, textOnly = textOnly,
        )
    }

    private val caughtUp = Store.Counts(mapOf(FileState.INDEXED to 11_530L), mapOf(Kind.PHOTO to 9_000L), 0)
    private val idle = IndexState(lastStop = StopReason.DONE, backend = Backend.GPU)
    private val mediaOnly = Access(false, Photos.ALL, videos = true, audio = true)
    private val withFolders = Access(
        false,
        Photos.SELECTED,
        folders = listOf(
            PickedFolder("content://com.android.externalstorage.documents/tree/primary%3ADocuments", "Documents"),
            PickedFolder("content://com.android.externalstorage.documents/tree/1A2B-3C4D%3APapers", "SD card/Papers"),
        ),
    )

    private fun coverage(name: String, access: Access, dark: Boolean) =
        status(name, counts = caughtUp, index = idle, dark = dark, access = access, tall = true)

    @Test fun statusCoveragePhotosOnly() = coverage("status_coverage_photos_only_light", mediaOnly, dark = false)
    @Test fun statusCoveragePhotosOnlyDark() = coverage("status_coverage_photos_only_dark", mediaOnly, dark = true)
    @Test fun statusCoverageFolders() = coverage("status_coverage_folders_light", withFolders, dark = false)
    @Test fun statusCoverageFoldersDark() = coverage("status_coverage_folders_dark", withFolders, dark = true)

    private val textOnlyCounts = Store.Counts(mapOf(FileState.INDEXED to 2_140L, FileState.PENDING to 310L), mapOf(Kind.PDF to 900L), 0)
    @Test fun statusTextOnly() = status("status_text_only_light", textOnlyCounts, idle, textOnly = true, tall = true)
    @Test fun statusTextOnlyDark() = status("status_text_only_dark", textOnlyCounts, idle, dark = true, textOnly = true, tall = true)

    private val tail = reading.copy(tailFiles = 7)
    @Test fun statusTailFiles() = status("status_tail_files_light", tail, running.copy(running = false, lastStop = StopReason.BUDGET))
    @Test fun statusTailFilesDark() = status("status_tail_files_dark", tail, running.copy(running = false, lastStop = StopReason.BUDGET), dark = true)

    @Test fun statusHoldsNoPreferenceAndLinksToSettings() {
        var opened = 0
        shot("status_links_settings_light", tall = true) {
            StatusContent(caughtUp, prefs, idle, skippedFiles, false, batteryHint = false, actions = StatusActions(openSettings = { opened++ }))
        }
        listOf("Only read while charging", "Screenshots", "PDFs", "Tune again", "Folders Retriever skips", "Read inside", "Sample mode")
            .forEach { rule.onAllNodesWithText(it, substring = true).assertCountEquals(0) }
        rule.onNodeWithText("Settings").performClick()
        assertEquals(1, opened)
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
