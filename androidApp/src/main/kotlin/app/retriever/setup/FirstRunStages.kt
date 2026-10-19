/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// DownloadProgress adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/DownloadAndTryButton.kt (Apache-2.0). Modified for Retriever: only the in-progress download branch, driven by ModelDownload's polled progress.

package app.retriever.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.retriever.R
import app.retriever.access.Fix
import app.retriever.core.Kind
import app.retriever.core.TasteProgress
import app.retriever.model.ModelState
import app.retriever.model.TuneState
import app.retriever.status.textOnlyNote
import app.retriever.ui.Format
import app.retriever.ui.components.Muted
import app.retriever.ui.tabular
import java.text.NumberFormat

/** Two choices and no Skip: either tap starts the download. */
@Composable
internal fun ColumnScope.Welcome(ui: SetupUi, size: String?, actions: SetupActions) {
    Text("Find anything by what's inside it", style = MaterialTheme.typography.headlineMedium)
    Text(
        "Search your photos, screenshots, PDFs and documents in your own words. Everything is read on this phone and stays on it.",
        style = MaterialTheme.typography.bodyLarge,
    )
    WelcomeExamples(ui.textOnly)
    Button(onClick = actions.getStarted, modifier = Modifier.fillMaxWidth()) { Text("Try it on a few files first") }
    TextButton(onClick = actions.readEverything, modifier = Modifier.fillMaxWidth()) { Text("Read everything now") }
    MaterialTheme(typography = MaterialTheme.typography.copy(bodyMedium = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center))) {
        Muted(
            if (ui.textOnly) {
                "Ready in a few minutes on a handful of your files. Reading everything takes hours, and you choose when."
            } else {
                "A one-time ${size?.let { "$it " } ?: ""}download, then a few minutes on a handful of your files. " +
                    "Reading everything takes hours, and you choose when."
            },
            Modifier.fillMaxWidth(),
        )
    }
    if (ui.textOnly) Muted(textOnlyNote(ui.ramGb))
}

/** No file content here: only the ways to allow access, and how the download is doing. */
@Composable
internal fun ColumnScope.AccessStage(ui: SetupUi, filesDenied: Boolean, folderError: String?, actions: SetupActions) {
    Text("Allow access to your files", style = MaterialTheme.typography.headlineSmall)
    Button(onClick = actions.allowFiles, modifier = Modifier.fillMaxWidth()) { Text("Allow access to all files") }
    Muted("Recommended: Retriever can then search your documents, downloads and everything else.")
    OutlinedButton(onClick = actions.allowMedia, modifier = Modifier.fillMaxWidth()) { Text("Only photos, videos and audio") }
    OutlinedButton(onClick = actions.addFolder, modifier = Modifier.fillMaxWidth()) { Text("Add a folder of documents") }
    if (filesDenied) Muted("Nothing is allowed yet. Any one of these is enough to start.")
    Muted("Files inside other apps' private folders can't be searched.")
    folderError?.let { ErrorText(it) }
    if (!ui.textOnly) DownloadLine(ui)
}

/** One line at the foot of the access screen: the download runs while the person decides. */
@Composable
private fun DownloadLine(ui: SetupUi) {
    when (val m = ui.model) {
        is ModelState.Downloading -> {
            val percent = percentFormat().format(m.progress)
            Muted(if (m.indeterminate) "Downloading" else "Downloading · $percent")
            DownloadBar(m, Modifier.fillMaxWidth())
        }

        ModelState.WaitingForWifi -> Muted("Waiting for Wi-Fi")

        ModelState.Checking -> Muted("Checking the download…")

        ModelState.Done -> Muted("Downloaded")

        is ModelState.Failed -> ErrorText(m.reason)

        ModelState.Missing -> if (ui.downloadStopped) Muted("Download stopped.")
    }
}

/** The download, then the tune. Names are searchable throughout. */
@Composable
internal fun Waiting(ui: SetupUi, size: String?, folderError: String?, actions: SetupActions) {
    val downloading = ui.model != ModelState.Done
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            if (downloading) "Getting Retriever ready to read" else "Getting to know this phone",
            style = MaterialTheme.typography.headlineSmall,
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (val m = ui.model) {
                    is ModelState.Downloading -> DownloadProgress(m, ui.build?.sizeBytes, actions.stopDownload)

                    ModelState.WaitingForWifi -> {
                        Muted("Waiting for Wi-Fi. It starts by itself when you're on Wi-Fi.")
                        TextButton(onClick = actions.useMobileData, modifier = Modifier.align(Alignment.End)) { Text("Use mobile data") }
                    }

                    ModelState.Checking -> Muted("Checking the download…")

                    is ModelState.Failed -> {
                        ErrorText(m.reason)
                        OutlinedButton(onClick = actions.retry) { Text("Try again") }
                    }

                    ModelState.Missing -> if (ui.downloadStopped) {
                        Muted("Download stopped.")
                        OutlinedButton(onClick = actions.retry) { Text("Download") }
                    }

                    ModelState.Done -> if (ui.tune is TuneState.Failed) {
                        ErrorText("Couldn't finish getting to know this phone.")
                        OutlinedButton(onClick = actions.retry) { Text("Try again") }
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Getting to know this phone" },
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                        Muted("About a minute. Retriever times a few reads to pick the fastest way to run on this phone.")
                    }
                }
                if (ui.fellBack && downloading) {
                    Muted(
                        "This phone's AI chip couldn't run it, so Retriever will use the graphics chip instead. " +
                            "That needs one more download${size?.let { " ($it)" } ?: ""}.",
                    )
                }
            }
        }
        if (downloading) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Why this download", style = MaterialTheme.typography.titleSmall)
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
                    DownloadReason(
                        R.drawable.ic_search,
                        "It understands what's inside",
                        "So “sunset at the beach” finds the photo, even when the file is called IMG_2041.",
                    )
                    DownloadReason(
                        R.drawable.ic_lock,
                        "It runs on this phone",
                        "Everything is read on this phone and stays on it. That's why it's big: the whole model lives here.",
                    )
                    DownloadReason(
                        R.drawable.ic_download_done,
                        "You download it once",
                        "${size?.let { "$it, one time." } ?: "A one-time download."} Search works without the internet after this.",
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("While you wait", style = MaterialTheme.typography.titleSmall)
            Muted("Names are searchable now.")
            FilledTonalButton(onClick = actions.search) { Text("Search by name") }
            if (!ui.access.allFiles) {
                val lines = ui.access.coverage()
                val covered = lines.filter { !it.gap }.map { it.text } + ui.access.folders.map { it.name }
                Muted("Retriever can search: " + covered.joinToString(" · "))
                if (lines.any { it.fix == Fix.ALLOW_MEDIA }) {
                    OutlinedButton(onClick = actions.allowMedia) { Text("Allow photos, videos and music") }
                }
                OutlinedButton(onClick = actions.addFolder) { Text("Add a folder of documents") }
                folderError?.let { ErrorText(it) }
            }
        }
        if (ui.notificationAsk) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Show reading progress in a notification?", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = actions.allowNotifications) { Text("Allow") }
                        TextButton(onClick = actions.dismissNotifications) { Text("Not now") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadReason(icon: Int, headline: String, supporting: String) {
    ListItem(
        headlineContent = { Text(headline) },
        supportingContent = { Text(supporting) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** The file being read, the files ready to search, and progress through each kind. */
@Composable
internal fun ColumnScope.ReadingFew(taste: TasteProgress, reading: @Composable (Int) -> Unit) {
    val planned = taste.byKind.values.sumOf { it.second }
    Text("Reading a few of your files", style = MaterialTheme.typography.headlineSmall)
    Muted(listOfNotNull("$planned files", timeLeft(taste.secondsLeft)).joinToString(" · "))
    reading(taste.read)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((name, kinds) in KIND_ROWS) {
            val (read, of) = kinds.mapNotNull(taste.byKind::get).fold(0 to 0) { (r, n), (a, b) -> r + a to n + b }
            if (of > 0) KindRow(name, read, of)
        }
    }
}

@Composable
internal fun TryNow(onTry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onClick = onTry, modifier = Modifier.fillMaxWidth()) { Text("Try it now") }
        Muted("Everything it reads now stays searchable.")
    }
}

private val KIND_ROWS = listOf(
    "Screenshots" to listOf(Kind.SCREENSHOT),
    "Documents and PDFs" to listOf(Kind.DOC, Kind.PDF),
    "Photos" to listOf(Kind.PHOTO),
    "Videos" to listOf(Kind.VIDEO),
)

internal fun timeLeft(seconds: Int?): String? = when {
    seconds == null || seconds <= 0 -> null
    seconds <= 10 -> "almost done"
    seconds < 60 -> "less than a minute left"
    seconds < 90 -> "about a minute left"
    else -> "about ${(seconds + 30) / 60} minutes left"
}

@Composable
private fun KindRow(name: String, read: Int, of: Int) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("$name $read/$of", style = MaterialTheme.typography.labelLarge.tabular())
            if (read == of) Icon(painterResource(R.drawable.ic_check_circle), contentDescription = "Ready to search", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun ErrorText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun DownloadBar(state: ModelState.Downloading, modifier: Modifier = Modifier) {
    val percent = percentFormat().format(state.progress)
    val bar = modifier.semantics {
        contentDescription = if (state.indeterminate) "Starting the download" else "Downloading, $percent"
        if (!state.indeterminate) progressBarRangeInfo = ProgressBarRangeInfo(state.progress, 0f..1f)
    }
    if (state.indeterminate) {
        LinearProgressIndicator(modifier = bar, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
    } else {
        LinearProgressIndicator(progress = { state.progress }, modifier = bar, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
    }
}

@Composable
private fun DownloadProgress(state: ModelState.Downloading, totalBytes: Long?, onStop: () -> Unit) {
    val context = LocalContext.current
    val total = totalBytes?.takeIf { it > 0 }
    val progress = state.progress.coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (total != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${Format.downloadSize(context, (progress * total).toLong())} of ${Format.downloadSize(context, total)}",
                    style = MaterialTheme.typography.bodyMedium.tabular(),
                    modifier = Modifier.weight(1f),
                )
                Text(percentFormat().format(progress), style = MaterialTheme.typography.bodyMedium.tabular())
                TextButton(onClick = onStop) { Text("Stop") }
            }
        }
        DownloadBar(state.copy(progress = progress, indeterminate = total == null || state.indeterminate), Modifier.fillMaxWidth())
        if (total == null) TextButton(onClick = onStop, modifier = Modifier.align(Alignment.End)) { Text("Stop") }
    }
}

@Composable
private fun percentFormat(): NumberFormat {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { NumberFormat.getPercentInstance(locale) }
}
