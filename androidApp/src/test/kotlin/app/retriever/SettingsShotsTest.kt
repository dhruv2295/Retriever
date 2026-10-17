package app.retriever

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.retriever.core.Backend
import app.retriever.core.IndexState
import app.retriever.core.Kind
import app.retriever.core.ModelBuilds
import app.retriever.core.Prefs
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
        index = IndexState(backend = Backend.GPU, detail = "the vision encoder runs on the processor"),
        storage = storage,
        build = build,
    )

    private fun shot(name: String, dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        // Tall enough for the whole page, so the rows below the fold are in the shot too.
        RuntimeEnvironment.setQualifiers(if (fontScale != 1f) "+h2400dp" else "+h1900dp")
        rule.setContent { ShotTheme(dark, fontScale, content) }
        rule.shot(name)
    }

    private fun page(name: String, ui: SettingsUi, dark: Boolean = false, fontScale: Float = 1f) =
        shot(name, dark, fontScale) { SettingsContent(ui) }

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
        rule.onNodeWithText("Remove the search model?").assertExists()
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
    @Test fun textOnlyPhone() = page("settings_text_only_light", ready.copy(textOnly = true, ramGb = 3.0, readable = setOf(Kind.PDF, Kind.DOC, Kind.SCREENSHOT)))
    @Test fun noticesPage() = shot("settings_notices_light") { NoticesContent("# Third-party notices\n\nRetriever includes code adapted from the open-source projects below.") {} }

    @Test fun switchesCallTheirActions() {
        val seen = mutableListOf<String>()
        rule.setContent {
            ShotTheme(dark = false) {
                SettingsContent(
                    ready,
                    actions = SettingsActions(
                        setOnlyWhileCharging = { seen += "charging=$it" },
                        setRememberSearches = { seen += "remember=$it" },
                        setMobileData = { seen += "mobile=$it" },
                        setDynamicColor = { seen += "dynamic=$it" },
                        setSearchInside = { k, on -> seen += "${k.name}=$on" },
                        askFreeUpSpace = { seen += "ask" },
                    ),
                )
            }
        }
        for (label in listOf("Only read while charging", "Remember searches", "Use mobile data", "Wallpaper colours", "PDFs", "Free up space")) {
            rule.onNodeWithText(label).performScrollTo().performClick()
        }
        assertEquals(
            listOf("charging=true", "remember=false", "mobile=true", "dynamic=false", "${Kind.PDF.name}=false", "ask"),
            seen,
        )
    }

    @Test fun switchesShowThePrefs() {
        rule.setContent {
            ShotTheme(dark = false) {
                SettingsContent(ready.copy(prefs = ready.prefs.copy(rememberSearches = false, onlyWhileCharging = true)))
            }
        }
        rule.onNodeWithText("Remember searches").performScrollTo().assertIsOff()
        rule.onNodeWithText("Only read while charging").performScrollTo().assertIsOn()
        rule.onNodeWithText("Wallpaper colours").performScrollTo().assertIsOn()
    }

    @Test fun modelRowsFollowTheModel() {
        rule.setContent { ShotTheme(dark = false) { SettingsContent(ready.copy(storage = storage.copy(modelBytes = 0), modelMissing = true)) } }
        rule.onNodeWithText("Not downloaded").assertExists()
        rule.onNodeWithText("Download again").assertExists()
        assertTrue(rule.onAllNodesWithTextCount("Free up space") == 0)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
