package app.retriever.settings

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
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
import app.retriever.core.Kind
import app.retriever.core.ModelBuilds
import app.retriever.core.Prefs
import app.retriever.model.ModelState
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
    )

    private fun shot(name: String, dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        // Tall enough for the whole page, so the rows below the fold are in the shot too.
        RuntimeEnvironment.setQualifiers(if (fontScale != 1f) "+h2400dp" else "+h1900dp")
        rule.setContent { ShotTheme(dark, fontScale, content) }
        rule.shot(name)
    }

    private fun page(name: String, ui: SettingsUi, dark: Boolean = false, fontScale: Float = 1f) = shot(name, dark, fontScale) { SettingsContent(ui) }

    @Test fun settingsLight() = page("settings_light", ready)

    @Test fun settingsDark() = page("settings_dark", ready, dark = true)

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
        ready.copy(storage = storage.copy(modelBytes = 0, cacheBytes = 0), modelMissing = true),
    )

    @Test fun downloadingAgain() = page(
        "settings_downloading_dark",
        ready.copy(storage = storage.copy(modelBytes = 0), modelMissing = true, download = ModelState.Downloading(0.45f, false)),
        dark = true,
    )

    @Test fun downloadFailed() = page(
        "settings_download_failed_light",
        ready.copy(modelMissing = true, download = ModelState.Failed(spaceMessage(build))),
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
        rule.onNodeWithText("Automatic reading").performScrollTo().performClick()
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
        rule.onNodeWithText("While charging").performScrollTo().assertExists()
        rule.onNodeWithText("Wallpaper colours").performScrollTo().assertIsOn()
    }

    @Test fun modelRowsFollowTheModel() {
        rule.setContent { ShotTheme(dark = false) { SettingsContent(ready.copy(storage = storage.copy(modelBytes = 0), modelMissing = true)) } }
        rule.onNodeWithText("Not downloaded").assertExists()
        rule.onNodeWithText("Download again").assertExists()
        assertTrue(rule.onAllNodesWithTextCount("Remove search download") == 0)
    }

    private fun show(ui: SettingsUi = ready, actions: SettingsActions = SettingsActions()) =
        rule.setContent { ShotTheme(dark = false) { SettingsContent(ui, actions = actions) } }

    private fun top(text: String) = rule.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

    @Test fun groupsComeInTheSpecifiedOrder() {
        show()
        val order = listOf("Reading", "Included files", "Privacy", "Storage and downloads", "Appearance", "About").map(::top)
        assertEquals(order.sorted(), order)
        assertEquals(order.distinct(), order)
        // On this day lives in Appearance, below Storage, not in Reading status.
        assertTrue(top("Show On this day") > top("Appearance") && top("Show On this day") < top("About"))
    }

    @Test fun includedFilesHoldAccessTypesAndExcludedFolders() {
        val seen = mutableListOf<String>()
        show(actions = SettingsActions(openAccess = { seen += "access" }, removeExcluded = { seen += "include $it" }))

        rule.onNodeWithText("Manage access").performScrollTo().performClick()
        rule.onNodeWithText("Read these file types").assertExists()
        rule.onNodeWithText("Excluded folders").assertExists()
        rule.onNodeWithText(INCLUDED_FILES_FOOTER).assertExists()
        rule.onNodeWithText("Include folder").performScrollTo().performClick()

        assertEquals(listOf("access", "include Android/media/WhatsApp/Stickers"), seen)
        assertEquals(0, rule.onAllNodesWithTextCount("Folders Retriever skips"))
    }

    @Test fun audioIsNameOnlyNotASwitch() {
        show()
        rule.onNodeWithText("Audio").performScrollTo().assertExists()
        rule.onNodeWithText("Name only on this device").assertExists()
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

        rule.onNodeWithText("Reading notifications").performScrollTo().performClick()

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
        rule.onNodeWithText("Saved search data · ${Format.bytes(storage.indexBytes)}").performScrollTo().assertExists()
        rule.onNodeWithText("Search by meaning and what's in your photos · ${Format.downloadSize(context, build.sizeBytes)}").assertExists()
        rule.onNodeWithText("Downloaded").assertExists()
    }

    @Test fun theDownloadEntryKeepsItsSizeWhenTheModelIsNotThere() {
        show(ready.copy(storage = storage.copy(modelBytes = 0), modelMissing = true))
        val context = RuntimeEnvironment.getApplication()
        rule.onNodeWithText("Search by meaning and what's in your photos · ${Format.downloadSize(context, build.sizeBytes)}").performScrollTo().assertExists()
        rule.onNodeWithText("Not downloaded").assertExists()
    }

    @Test fun cancellingADownloadSaysCancelDownload() {
        var cancelled = 0
        show(ready.copy(modelMissing = true, download = ModelState.Downloading(0.45f, false)), SettingsActions(cancelDownload = { cancelled++ }))
        rule.onNodeWithText("Cancel download").performScrollTo().performClick()
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
            "File names and text already read still work. Search by meaning needs the download again.",
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
        rule.onNodeWithText("Show On this day").performScrollTo().performClick()
        assertTrue(restored)
        rule.onAllNodesWithText("Wallpaper colours").assertCountEquals(0)
    }
}
