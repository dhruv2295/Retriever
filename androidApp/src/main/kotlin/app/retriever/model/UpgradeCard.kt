package app.retriever.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.retriever.core.DeviceWords
import app.retriever.core.UpgradeStage
import app.retriever.core.Words
import app.retriever.ui.Format
import app.retriever.ui.components.Muted
import app.retriever.ui.tabular
import java.text.NumberFormat

/** What the upgrade card can ask for; every default does nothing, so a test passes only what it checks. */
class UpgradeActions(
    val download: () -> Unit = {},
    /** Null hides Not now (Settings has no offer to dismiss). */
    val notNow: (() -> Unit)? = null,
    val cancel: () -> Unit = {},
    val useMobileData: () -> Unit = {},
    val waitForWifi: () -> Unit = {},
    val retryTune: () -> Unit = {},
)

fun UpgradeFlow.actions(dismissible: Boolean) = UpgradeActions(
    download = ::download,
    notNow = if (dismissible) ({ dismissOffer() }) else null,
    cancel = { cancel() },
    useMobileData = ::useMobileData,
    waitForWifi = ::waitForWifi,
    retryTune = ::retryTune,
)

/** The offer, then the download, the tune and the meaning pass, one step at a time; nothing once done or unavailable. */
@Composable
fun UpgradeCard(ui: UpgradeUi, actions: UpgradeActions, modifier: Modifier = Modifier) {
    if (ui.stage == UpgradeStage.UNAVAILABLE || ui.stage == UpgradeStage.DONE) return
    val context = LocalContext.current
    val size = Format.downloadSize(context, ui.sizeBytes)
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (ui.stage) {
                UpgradeStage.OFFER -> {
                    Title(Words.UPGRADE_TITLE)
                    Text(Words.upgradeBody(size, DeviceWords.ANDROID), style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = actions.download) { Text(Words.DOWNLOAD) }
                        actions.notNow?.let { TextButton(onClick = it) { Text(Words.NOT_NOW) } }
                    }
                }

                UpgradeStage.DOWNLOAD -> {
                    Title(Words.UPGRADE_TITLE)
                    Download(ui, actions)
                }

                UpgradeStage.TUNE -> {
                    Title(Words.tuneTitle(DeviceWords.ANDROID))
                    if (ui.tune is TuneState.Failed) {
                        Text("Couldn’t finish setting up reading.", color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = actions.retryTune) { Text(Words.TRY_AGAIN) }
                    } else {
                        Text(Words.tuneBody(DeviceWords.ANDROID), style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = Words.tuneTitle(DeviceWords.ANDROID) })
                        Muted(Words.TUNE_LEAVING)
                    }
                }

                UpgradeStage.MEANING -> ui.progress?.let { p ->
                    val read = p.contentRead.coerceAtLeast(1)
                    val fraction = ((p.contentRead - p.meaningWaiting).toFloat() / read).coerceIn(0f, 1f)
                    Text(Words.addingMeaning(p), style = MaterialTheme.typography.bodyMedium.tabular())
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) },
                    )
                }

                UpgradeStage.UNAVAILABLE, UpgradeStage.DONE -> Unit
            }
        }
    }
    if (ui.askMobileData) {
        AlertDialog(
            onDismissRequest = actions.waitForWifi,
            title = { Text(Words.mobileDataAsk(size)) },
            confirmButton = { TextButton(onClick = actions.useMobileData) { Text(Words.DOWNLOAD) } },
            dismissButton = { TextButton(onClick = actions.waitForWifi) { Text(Words.WAIT_FOR_WIFI) } },
        )
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Download(ui: UpgradeUi, actions: UpgradeActions) {
    // Only the state line is live, so TalkBack says the step and not each percent.
    val live = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    when (val m = ui.model) {
        is ModelState.Downloading -> {
            val context = LocalContext.current
            val progress = m.progress.coerceIn(0f, 1f)
            val percent = percentFormat()
            if (m.indeterminate || ui.sizeBytes <= 0) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                Text(
                    Words.downloadProgress(
                        Format.downloadSize(context, (progress * ui.sizeBytes).toLong()),
                        Format.downloadSize(context, ui.sizeBytes),
                        (progress * 100).toInt(),
                    ),
                    style = MaterialTheme.typography.bodyMedium.tabular(),
                )
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "Downloading, ${percent.format(progress)}"
                        progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                    },
                )
            }
            TextButton(onClick = actions.cancel) { Text(Words.CANCEL) }
        }

        ModelState.WaitingForWifi -> {
            Text(Words.WAITING_FOR_WIFI, live)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions.useMobileData) { Text(Words.USE_MOBILE_DATA) }
                TextButton(onClick = actions.cancel) { Text(Words.CANCEL) }
            }
        }

        ModelState.Checking -> {
            Text(Words.CHECKING_DOWNLOAD, live)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        is ModelState.Failed -> {
            Text(if (m.reason == ModelRepository.STOPPED) Words.DOWNLOAD_FAILED else m.reason, live, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = actions.download) { Text(Words.TRY_AGAIN) }
        }

        ModelState.Missing, ModelState.Done -> Unit
    }
}

@Composable
private fun percentFormat(): NumberFormat {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { NumberFormat.getPercentInstance(locale) }
}
