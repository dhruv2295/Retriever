package app.retriever.model

import android.os.storage.StorageManager
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.retriever.ShotTheme
import app.retriever.core.DeviceWords
import app.retriever.core.ReadingProgress
import app.retriever.core.UpgradeStage
import app.retriever.core.Words
import app.retriever.shot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Every step of "Search by meaning and what's in your photos", as Home, Try and Settings show it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UpgradeCardTest {
    @get:Rule val rule = createComposeRule()

    private val size = 387_710_976L
    private val calls = mutableListOf<String>()
    private val actions = UpgradeActions(
        download = { calls += "download" },
        notNow = { calls += "not now" },
        cancel = { calls += "cancel" },
        useMobileData = { calls += "mobile" },
        waitForWifi = { calls += "wifi" },
        retryTune = { calls += "retune" },
    )

    private fun show(name: String, ui: UpgradeUi, dark: Boolean = false) {
        rule.setContent { ShotTheme(dark) { UpgradeCard(ui, actions) } }
        rule.shot(name)
    }

    @Test fun offerDownloadsOrWaits() {
        show("upgrade_offer_light", UpgradeUi(UpgradeStage.OFFER, sizeBytes = size))
        rule.onNodeWithText(Words.UPGRADE_TITLE).assertExists()
        rule.onNodeWithText(Words.upgradeBody("388 MB", DeviceWords.ANDROID)).assertExists()
        rule.onNodeWithText(Words.DOWNLOAD).performClick()
        rule.onNodeWithText(Words.NOT_NOW).performClick()
        assertEquals(listOf("download", "not now"), calls)
    }

    @Test fun offerDark() = show("upgrade_offer_dark", UpgradeUi(UpgradeStage.OFFER, sizeBytes = size), dark = true)

    @Test fun downloadingShowsBytesPercentAndCancel() {
        show("upgrade_downloading_light", UpgradeUi(UpgradeStage.DOWNLOAD, ModelState.Downloading(0.5f, false), sizeBytes = size))
        rule.onNodeWithText("194 MB of 388 MB · 50%").assertExists()
        rule.onNodeWithText(Words.CANCEL).performClick()
        assertEquals(listOf("cancel"), calls)
    }

    @Test fun waitingForWifiOffersMobileData() {
        show("upgrade_wifi_light", UpgradeUi(UpgradeStage.DOWNLOAD, ModelState.WaitingForWifi, sizeBytes = size))
        rule.onNodeWithText(Words.WAITING_FOR_WIFI).assertExists()
        rule.onNodeWithText(Words.USE_MOBILE_DATA).performClick()
        assertEquals(listOf("mobile"), calls)
    }

    @Test fun mobileDataAsksFirst() {
        show("upgrade_ask_mobile_light", UpgradeUi(UpgradeStage.OFFER, sizeBytes = size, askMobileData = true))
        rule.onNodeWithText(Words.mobileDataAsk("388 MB")).assertExists()
        rule.onNodeWithText(Words.WAIT_FOR_WIFI).performClick()
        assertEquals(listOf("wifi"), calls)
    }

    @Test fun checking() {
        show("upgrade_checking_light", UpgradeUi(UpgradeStage.DOWNLOAD, ModelState.Checking, sizeBytes = size))
        rule.onNodeWithText(Words.CHECKING_DOWNLOAD).assertExists()
    }

    @Test fun failedTriesAgain() {
        show("upgrade_failed_light", UpgradeUi(UpgradeStage.DOWNLOAD, ModelState.Failed(ModelRepository.STOPPED), sizeBytes = size))
        rule.onNodeWithText(Words.DOWNLOAD_FAILED).assertExists()
        rule.onAllNodesWithText(Words.FREE_UP_SPACE).assertCountEquals(0)
        rule.onNodeWithText(Words.TRY_AGAIN).performClick()
        assertEquals(listOf("download"), calls)
    }

    @Test fun noSpaceOpensAndroidsStorageManager() {
        val reason = "Needs 0.7 GB free. Free up some space, then try again."
        show("upgrade_no_space_light", UpgradeUi(UpgradeStage.DOWNLOAD, ModelState.Failed(reason, noSpace = true), sizeBytes = size))
        rule.onNodeWithText(reason).assertExists()
        rule.onNodeWithText(Words.FREE_UP_SPACE).performClick()
        assertEquals(StorageManager.ACTION_MANAGE_STORAGE, shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity?.action)
        rule.onNodeWithText(Words.TRY_AGAIN).performClick()
        assertEquals(listOf("download"), calls)
    }

    @Test fun tuning() {
        show("upgrade_tune_light", UpgradeUi(UpgradeStage.TUNE, ModelState.Done, TuneState.Running, size))
        rule.onNodeWithText(Words.tuneTitle(DeviceWords.ANDROID)).assertExists()
        rule.onNodeWithText(Words.tuneBody(DeviceWords.ANDROID)).assertExists()
    }

    @Test fun tuneFailedTriesAgain() {
        show("upgrade_tune_failed_light", UpgradeUi(UpgradeStage.TUNE, ModelState.Done, TuneState.Failed("x"), size))
        rule.onNodeWithText(Words.TRY_AGAIN).performClick()
        assertEquals(listOf("retune"), calls)
    }

    @Test fun meaningPassCounts() {
        val p = ReadingProgress(read = 40, partlyRead = 0, scheduled = 0, notScheduled = 0, nameOnly = 0, couldntRead = 0, meaningWaiting = 15)
        show("upgrade_meaning_light", UpgradeUi(UpgradeStage.MEANING, ModelState.Done, sizeBytes = size, progress = p))
        rule.onNodeWithText(Words.addingMeaning(p)).assertExists()
    }

    @Test fun doneShowsNothing() {
        // Nothing is drawn, so there is no shot to take.
        rule.setContent { ShotTheme(false) { UpgradeCard(UpgradeUi(UpgradeStage.DONE, ModelState.Done, sizeBytes = size), actions) } }
        rule.onAllNodesWithText(Words.UPGRADE_TITLE).assertCountEquals(0)
    }
}
