package app.retriever.model

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.storage.StorageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

/**
 * The offer, then the download, the tune and the meaning pass, one step at a time; nothing once done or unavailable.
 * [compact]: Home's optional row, flat and with a tonal Download, so it never reads as something you must do.
 */
@Composable
fun UpgradeCard(ui: UpgradeUi, actions: UpgradeActions, modifier: Modifier = Modifier, compact: Boolean = false) {
    if (ui.stage == UpgradeStage.UNAVAILABLE || ui.stage == UpgradeStage.DONE) return
    val context = LocalContext.current
    val size = Format.downloadSize(context, ui.sizeBytes)
    Surface(
        color = if (compact) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (ui.stage) {
                UpgradeStage.OFFER -> {
                    Title(Words.UPGRADE_TITLE)
                    Text(Words.upgradeBody(size, DeviceWords.ANDROID), style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (compact) {
                            FilledTonalButton(onClick = actions.download) { Text(Words.DOWNLOAD) }
                        } else {
                            Button(onClick = actions.download) { Text(Words.DOWNLOAD) }
                        }
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
                        Text(Words.tuneFailed(DeviceWords.ANDROID), color = MaterialTheme.colorScheme.error)
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
    MobileDataAsk(ui, actions)
}

/** Download on mobile data? Asked wherever the download starts, the card's button or a status line's Try again. */
@Composable
fun MobileDataAsk(ui: UpgradeUi, actions: UpgradeActions) {
    if (!ui.askMobileData) return
    val size = Format.downloadSize(LocalContext.current, ui.sizeBytes)
    AlertDialog(
        onDismissRequest = actions.waitForWifi,
        title = { Text(Words.mobileDataAsk(size)) },
        confirmButton = { TextButton(onClick = actions.useMobileData) { Text(Words.DOWNLOAD) } },
        dismissButton = { TextButton(onClick = actions.waitForWifi) { Text(Words.WAIT_FOR_WIFI) } },
    )
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
                        contentDescription = Words.downloading(percent.format(progress))
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions.download) { Text(Words.TRY_AGAIN) }
                if (m.noSpace) {
                    val context = LocalContext.current
                    TextButton(onClick = { openStorageManager(context) }) { Text(Words.FREE_UP_SPACE) }
                }
            }
        }

        ModelState.Missing, ModelState.Done -> Unit
    }
}

/** Android's own screen for freeing space (developer.android.com/training/data-storage/app-specific#query-free-space). */
private fun openStorageManager(context: Context) {
    try {
        context.startActivity(Intent(StorageManager.ACTION_MANAGE_STORAGE))
    } catch (_: ActivityNotFoundException) {
    }
}

@Composable
private fun percentFormat(): NumberFormat {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { NumberFormat.getPercentInstance(locale) }
}
