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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.retriever.R
import app.retriever.access.Fix
import app.retriever.core.Kind
import app.retriever.core.TasteProgress
import app.retriever.model.ModelState
import app.retriever.model.TuneState
import app.retriever.status.textOnlyNote
import app.retriever.ui.components.Muted
import app.retriever.ui.components.kindIcon
import app.retriever.ui.tabular

/** Two choices and no Skip: either tap starts the download. */
@Composable
internal fun ColumnScope.Welcome(ui: SetupUi, size: String?, actions: SetupActions) {
    Text("Find anything by what's inside it", style = MaterialTheme.typography.headlineMedium)
    Text(
        "Search your photos, screenshots, PDFs and documents in your own words. Everything is read on this phone and stays on it.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        when {
            ui.textOnly -> "Try it on a few of your files first. Reading everything takes hours, and you choose when."

            else ->
                "Try it on a few of your files first. It's ready in a few minutes on Wi-Fi; most of that is a one-time " +
                    "${size ?: "model"} download. Reading everything takes hours, and you choose when."
        },
        style = MaterialTheme.typography.bodyLarge,
    )
    Button(onClick = actions.getStarted, modifier = Modifier.fillMaxWidth()) { Text("Try it on a few files first") }
    TextButton(onClick = actions.readEverything, modifier = Modifier.fillMaxWidth()) { Text("Read everything now") }
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
            val percent = (m.progress * 100).toInt()
            Muted(if (m.indeterminate) "Downloading" else "Downloading · $percent %")
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
internal fun ColumnScope.Waiting(ui: SetupUi, size: String?, folderError: String?, actions: SetupActions) {
    val downloading = ui.model != ModelState.Done
    Text(
        when {
            !downloading -> "Getting to know this phone · about a minute"
            size != null -> "Downloading what Retriever needs · $size"
            else -> "Downloading what Retriever needs"
        },
        style = MaterialTheme.typography.headlineSmall,
    )
    if (ui.fellBack && downloading && size != null) {
        Muted("This phone's AI chip couldn't run it, so Retriever will use the graphics chip instead. That needs one more download ($size).")
    }
    when (val m = ui.model) {
        is ModelState.Downloading -> DownloadProgress(m, actions.stopDownload)

        ModelState.WaitingForWifi -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Waiting for Wi-Fi. It starts by itself when you're on Wi-Fi.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = actions.useMobileData) { Text("Use mobile data") }
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

        ModelState.Done -> when (ui.tune) {
            TuneState.Running -> LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Getting to know this phone" },
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )

            is TuneState.Failed -> {
                ErrorText("Couldn't finish getting to know this phone.")
                OutlinedButton(onClick = actions.retry) { Text("Try again") }
            }

            TuneState.Idle, is TuneState.Done -> Unit
        }
    }
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
    Muted(if (ui.firstRun && downloading) "Names are searchable now. Retriever reads a few files when the download finishes." else "Names are searchable now.")
    TextButton(onClick = actions.search) { Text("Search by name now") }
    if (ui.notificationAsk) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Show reading progress in a notification?", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = actions.allowNotifications) { Text("Allow") }
                TextButton(onClick = actions.dismissNotifications) { Text("Not now") }
            }
        }
    }
}

/** What kinds are being read, never which files: no thumbnails before the person searches. */
@Composable
internal fun ReadingFew(taste: TasteProgress, actions: SetupActions) {
    val planned = taste.byKind.values.sumOf { it.second }
    Text("Reading a few of your files", style = MaterialTheme.typography.headlineSmall)
    Muted(listOfNotNull("$planned files", timeLeft(taste.secondsLeft)).joinToString(" · "))
    for ((name, kinds) in KIND_ROWS) {
        val (read, of) = kinds.mapNotNull(taste.byKind::get).fold(0 to 0) { (r, n), (a, b) -> r + a to n + b }
        if (of > 0) KindRow(name, kinds.first(), read, of)
    }
    if (taste.readyToTry) {
        Button(onClick = actions.tryNow, modifier = Modifier.fillMaxWidth()) { Text("Try it now") }
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

/** Counts and the bar move without easing; a finished kind shows its check once. */
@Composable
private fun KindRow(name: String, kind: Kind, read: Int, of: Int) {
    val done = read == of
    Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(kindIcon(kind)), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (done) {
                Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("ready to search", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            } else {
                Text("$read of $of", style = MaterialTheme.typography.bodyMedium.tabular())
            }
        }
        if (!done) {
            LinearProgressIndicator(
                progress = { read.toFloat() / of },
                modifier = Modifier.fillMaxWidth().padding(start = 36.dp),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
internal fun ErrorText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun DownloadBar(state: ModelState.Downloading, modifier: Modifier = Modifier) {
    val percent = (state.progress * 100).toInt()
    val bar = modifier.semantics {
        contentDescription = if (state.indeterminate) "Starting the download" else "Downloading, $percent percent"
        if (!state.indeterminate) progressBarRangeInfo = ProgressBarRangeInfo(state.progress, 0f..1f)
    }
    if (state.indeterminate) {
        LinearProgressIndicator(modifier = bar, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
    } else {
        LinearProgressIndicator(progress = { state.progress }, modifier = bar, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
    }
}

@Composable
private fun DownloadProgress(state: ModelState.Downloading, onStop: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (!state.indeterminate) {
            Text(
                "${(state.progress * 100).toInt()} %",
                style = MaterialTheme.typography.bodyMedium.tabular(),
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        DownloadBar(state, Modifier.weight(1f))
        IconButton(
            onClick = onStop,
            colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.padding(start = 4.dp),
        ) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Stop the download") }
    }
}
