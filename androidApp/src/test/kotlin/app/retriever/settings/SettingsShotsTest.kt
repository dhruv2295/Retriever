package app.retriever.settings

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.retriever.ShotTheme
import app.retriever.core.Backend
import app.retriever.core.DeviceWords
import app.retriever.core.Kind
import app.retriever.core.ModelBuilds
import app.retriever.core.Prefs
import app.retriever.core.ReadingPolicy
import app.retriever.core.ReadingProgress
import app.retriever.core.StopReason
import app.retriever.core.UpgradeStage
import app.retriever.core.Words
import app.retriever.model.ModelState
import app.retriever.model.TuneState
import app.retriever.model.UpgradeActions
import app.retriever.model.UpgradeUi
import app.retriever.model.spaceMessage
import app.retriever.shot
import app.retriever.ui.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders Settings to androidApp/build/shots and checks that each row calls the right action. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsShotsTest {
    @get:Rule val rule = createComposeRule()

    private val build = ModelBuilds.npu.first().variant.generic
    private val storage = StorageUsage(indexBytes = 412_000_000, modelBytes = build.sizeBytes, cacheBytes = 38_000_000)
    private val ready = SettingsUi(
        prefs = Prefs(excludedFolders = setOf("Android/media/WhatsApp/Stickers")),
        backend = Backend.GPU,
        detail = "the vision encoder runs on the processor",
        storage = storage,
        build = build,
        upgrade = UpgradeUi(UpgradeStage.DONE, ModelState.Done, sizeBytes = build.sizeBytes),
        readingStatus = ReadingPolicy.decide(ReadingProgress(read = 1_200, scheduled = 2_100), StopReason.UNPLUGGED, false, false, DeviceWords.ANDROID),
        large = 212L to 41_000_000_000L,
    )

    /** No download yet, or removed: the same offer as Try and Home. */
    private val offered = ready.copy(
        storage = storage.copy(modelBytes = 0),
        modelMissing = true,
        upgrade = UpgradeUi(UpgradeStage.OFFER, ModelState.Missing, sizeBytes = build.sizeBytes),
    )

    private fun shot(name: String, dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        // Tall enough for the whole page, so the rows below the fold are in the shot too.
        RuntimeEnvironment.setQualifiers(if (fontScale != 1f) "+h2400dp" else "+h1900dp")
        rule.setContent { ShotTheme(dark, fontScale, content) }
        rule.shot(name)
    }

    private fun page(name: String, ui: SettingsUi, dark: Boolean = false, fontScale: Float = 1f) = shot(name, dark, fontScale) { SettingsContent(ui) }

    @Test fun settingsLight() {
        page("clarity_settings_light", ready)
        rule.onNodeWithText("Waiting until you plug in · 36% done").assertExists()
        rule.onNodeWithText(Words.FREE_UP_SPACE).assertExists()
        rule.onNodeWithText(Words.NEVER_DELETES).assertExists()
        listOf("session", "Name only", "Manage access", "Excluded", "Saved search data").forEach {
            rule.onAllNodesWithText(it, substring = true).assertCountEquals(0)
        }
    }

    @Test fun settingsDark() = page("clarity_settings_dark", ready, dark = true)

    /** Read for 15 minutes shows at once in the Reading line above it; the line opens Reading status. */
    @Test fun readForFifteenMinutesShowsInTheLine() {
        val seen = mutableListOf<String>()
        var ui by mutableStateOf(ready)
        val actions = SettingsActions(readNow = { seen += "read" }, stopReadNow = { seen += "stop" }, openStatus = { seen += "status" })
        rule.setContent { ShotTheme(dark = false) { SettingsContent(ui, actions = actions) } }
        rule.onNodeWithText(Words.READ_FOR_15).performScrollTo().performClick()
        ui =
            ready.copy(
                readingStatus = ReadingPolicy.decide(
                    ReadingProgress(read = 1_200, scheduled = 2_100),
                    null,
                    false,
                    false,
                    DeviceWords.ANDROID,
                    readNowMinutes = 15,
                ),
            )
        rule.onNodeWithText("Processing for 15 minutes · 15 min left").assertExists()
        rule.onNodeWithText("Stop").performClick()
        rule.onNodeWithText("Processing for 15 minutes · 15 min left").performClick()
        assertEquals(listOf("read", "stop", "status"), seen)
    }

    @Test fun freeUpSpaceOpensLargeFilesAndDuplicates() {
        val seen = mutableListOf<String>()
        show(actions = SettingsActions(openLarge = { seen += "large" }, openDuplicates = { seen += "copies" }))
        rule.onNodeWithText("${Words.largeFiles(212)} · ${Format.bytes(41_000_000_000)}").performScrollTo().performClick()
        rule.onNodeWithText(Words.FIND_DUPLICATES).performScrollTo().performClick()
        assertEquals(listOf("large", "copies"), seen)
    }

    @Test fun settingsBigText() = page("settings_text1_5", ready, fontScale = 1.5f)

    @Test fun settingsWithDialogLight() = dialog("settings_free_dialog_light", dark = false)

    @Test fun settingsWithDialogDark() = dialog("settings_free_dialog_dark", dark = true)

    private fun dialog(name: String, dark: Boolean) {
        shot(name, dark) { SettingsContent(ready.copy(confirmFree = true)) }
        // The dialog is its own window, so it gets its own picture beside the page's.
        val bitmap = rule.onNode(isDialog()).captureToImage().asAndroidBitmap()
        File("build/shots/${name}_dialog.png").also { it.parentFile?.mkdirs() }
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        rule.onNodeWithText("Remove the search download?").assertExists()
    }

    @Test fun modelRemoved() = page(
        "settings_model_removed_light",
        offered.copy(storage = storage.copy(modelBytes = 0, cacheBytes = 0)),
    )

    @Test fun downloadingAgain() = page(
        "settings_downloading_dark",
        offered.copy(upgrade = offered.upgrade.copy(stage = UpgradeStage.DOWNLOAD, model = ModelState.Downloading(0.45f, false))),
        dark = true,
    )

    @Test fun downloadFailed() = page(
        "settings_download_failed_light",
        offered.copy(upgrade = offered.upgrade.copy(stage = UpgradeStage.DOWNLOAD, model = ModelState.Failed(spaceMessage(build)))),
    )

    @Test fun textOnlyPhone() = page(
        "settings_text_only_light",
        ready.copy(textOnly = true, ramGb = 3.0, readable = setOf(Kind.PDF, Kind.DOC, Kind.SCREENSHOT)),
    )

    @Test fun noticesPage() = shot("settings_notices_light") {
        NoticesContent("# Third-party notices\n\nRetriever includes code adapted from the open-source projects below.") {}
    }

    @Test fun switchesCallTheirActions() {
        val seen = mutableListOf<String>()
        rule.setContent {
            ShotTheme(dark = false) {
                SettingsContent(
                    ready,
                    actions = SettingsActions(
                        setReadingSchedule = { seen += "schedule=${it.name}" },
                        setRememberSearches = { seen += "remember=$it" },
                        setMobileData = { seen += "mobile=$it" },
                        setDynamicColor = { seen += "dynamic=$it" },
                        setSearchInside = { k, on -> seen += "${k.name}=$on" },
                        askFreeUpSpace = { seen += "ask" },
                    ),
                )
            }
        }
        rule.onNodeWithText(Words.WHEN_TO_READ).performScrollTo().performClick()
        rule.onNodeWithText("Also on battery").performClick()
        for (label in listOf("Save recent searches", "Use mobile data for downloads", "Wallpaper colours", "PDFs", "Remove search download")) {
            rule.onNodeWithText(label).performScrollTo().performClick()
        }
        assertEquals(
            listOf("schedule=BATTERY", "remember=false", "mobile=true", "dynamic=false", "${Kind.PDF.name}=false", "ask"),
            seen,
        )
    }

    @Test fun switchesShowThePrefs() {
        rule.setContent {
            ShotTheme(dark = false) {
                SettingsContent(ready.copy(prefs = ready.prefs.copy(rememberSearches = false, onlyWhileCharging = true)))
            }
        }
        rule.onNodeWithText("Save recent searches").performScrollTo().assertIsOff()
        rule.onNodeWithText("While charging", substring = true).performScrollTo().assertExists()
        rule.onNodeWithText("Wallpaper colours").performScrollTo().assertIsOn()
    }

    @Test fun withNoDownloadSettingsOffersTheUpgradeAndNeverSaysDownloadAgain() {
        var downloads = 0
        show(offered, SettingsActions(upgrade = UpgradeActions(download = { downloads++ })))
        rule.onNodeWithText(Words.UPGRADE_TITLE).performScrollTo().assertExists()
        rule.onNodeWithText(Words.DOWNLOAD).performScrollTo().performClick()
        assertEquals(1, downloads)
        // Settings keeps the offer, so there is nothing to put off.
        for (gone in listOf(Words.NOT_NOW, "Not downloaded", "Download again", "Remove search download")) {
            assertEquals(gone, 0, rule.onAllNodesWithTextCount(gone))
        }
    }

    @Test fun theTuneShowsInSettingsToo() {
        show(offered.copy(upgrade = offered.upgrade.copy(stage = UpgradeStage.TUNE, model = ModelState.Done, tune = TuneState.Running)))
        rule.onNodeWithText(Words.tuneTitle(DeviceWords.ANDROID)).performScrollTo().assertExists()
    }

    private fun show(ui: SettingsUi = ready, actions: SettingsActions = SettingsActions()) =
        rule.setContent { ShotTheme(dark = false) { SettingsContent(ui, actions = actions) } }

    private fun top(text: String) = rule.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

    @Test fun groupsComeInTheSpecifiedOrder() {
        show()
        val order = listOf(Words.HOW_IT_WORKS, Words.READING_STATUS, "Included files", "Privacy", "Storage and downloads", "Appearance", "About").map(::top)
        assertEquals(order.sorted(), order)
        assertEquals(order.distinct(), order)
        // On this day lives in Appearance, below Storage, not in Reading status.
        assertTrue(top(Words.SHOW_ON_THIS_DAY) > top("Appearance") && top(Words.SHOW_ON_THIS_DAY) < top("About"))
    }

    @Test fun includedFilesHoldAccessTypesAndExcludedFolders() {
        val seen = mutableListOf<String>()
        show(actions = SettingsActions(openAccess = { seen += "access" }, removeExcluded = { seen += "include $it" }))

        rule.onNodeWithText(Words.WHAT_IT_CAN_SEE).performScrollTo().performClick()
        rule.onNodeWithText("Process these file types").assertExists()
        rule.onNodeWithText(Words.FOLDERS_TO_SKIP).assertExists()
        rule.onNodeWithText(INCLUDED_FILES_FOOTER).assertExists()
        rule.onNodeWithText("Include folder").performScrollTo().performClick()

        assertEquals(listOf("access", "include Android/media/WhatsApp/Stickers"), seen)
        assertEquals(0, rule.onAllNodesWithTextCount("Folders Retriever skips"))
    }

    @Test fun audioIsNameOnlyNotASwitch() {
        show()
        rule.onNodeWithText("Audio").performScrollTo().assertExists()
        rule.onNodeWithText(Words.FOUND_BY_NAME).assertExists()
        assertEquals(0, rule.onAllNodes(isToggleable() and hasText("Audio")).fetchSemanticsNodes().size)
    }

    @Test fun clearingRecentSearchesAsksFirst() {
        var cleared = 0
        show(actions = SettingsActions(clearRecentSearches = { cleared++ }))

        rule.onNodeWithText("Clear recent searches").performScrollTo().performClick()
        rule.onNodeWithText("Clear recent searches and shortcuts?").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(0, cleared)

        rule.onNodeWithText("Clear recent searches").performScrollTo().performClick()
        rule.onNodeWithText("Clear").performClick()
        assertEquals(1, cleared)
    }

    @Test fun readingNotificationsReportOnOrOffAndOpenTheSystemPage() {
        var opened = 0
        val actions = SettingsActions(openNotificationSettings = { opened++ })
        show(ready.copy(notificationsOn = false), actions)

        rule.onNodeWithText("Processing notifications").performScrollTo().performClick()

        assertEquals(1, opened)
        rule.onNodeWithText("Off").assertExists()
        assertEquals(0, rule.onAllNodesWithTextCount("On"))
    }

    @Test fun readingNotificationsSayOn() {
        show(ready.copy(notificationsOn = true))
        rule.onNodeWithText("On").performScrollTo().assertExists()
    }

    @Test fun storageNamesTheSavedDataAndTheOptionalDownloadWithTheirSizes() {
        show()
        val context = RuntimeEnvironment.getApplication()
        rule.onNodeWithText("${Words.whatItHasRead(DeviceWords.ANDROID)} · ${Format.bytes(storage.indexBytes)}").performScrollTo().assertExists()
        rule.onNodeWithText("${Words.UPGRADE_TITLE} · ${Format.downloadSize(context, build.sizeBytes)}").assertExists()
        rule.onNodeWithText("Downloaded").assertExists()
    }

    @Test fun theOfferKeepsItsSizeWhenTheModelIsNotThere() {
        show(offered)
        val size = Format.downloadSize(RuntimeEnvironment.getApplication(), build.sizeBytes)
        rule.onNodeWithText(Words.upgradeBody(size, DeviceWords.ANDROID)).performScrollTo().assertExists()
    }

    @Test fun cancellingADownloadGoesThroughTheUpgrade() {
        var cancelled = 0
        val downloading = offered.upgrade.copy(stage = UpgradeStage.DOWNLOAD, model = ModelState.Downloading(0.45f, false))
        show(offered.copy(upgrade = downloading), SettingsActions(upgrade = UpgradeActions(cancel = { cancelled++ })))
        rule.onNodeWithText(Words.CANCEL).performScrollTo().performClick()
        assertEquals(1, cancelled)
    }

    @Test fun removingTheDownloadConfirmsWithTheSizeItFrees() {
        var freed = 0
        show(ready.copy(confirmFree = true), SettingsActions(freeUpSpace = { freed++ }))
        val size = Format.downloadSize(RuntimeEnvironment.getApplication(), build.sizeBytes)

        rule.onNodeWithText("Remove the search download?").assertExists()
        rule.onNodeWithText(removeDownloadMessage(size)).assertExists()
        rule.onNodeWithText("Remove download").performClick()

        assertEquals(1, freed)
        assertEquals(
            "File names and text already processed still work. Search by meaning needs the download again.",
            removeDownloadMessage(null),
        )
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size

    @Test
    @Config(sdk = [30])
    fun onThisDayCanBeRestoredOnAndroidEleven() {
        var restored = false
        rule.setContent {
            ShotTheme(false) {
                SettingsContent(ready.copy(prefs = ready.prefs.copy(showOnThisDay = false)), actions = SettingsActions(setOnThisDay = { restored = it }))
            }
        }
        rule.onNodeWithText(Words.SHOW_ON_THIS_DAY).performScrollTo().performClick()
        assertTrue(restored)
        rule.onAllNodesWithText("Wallpaper colours").assertCountEquals(0)
    }
}
