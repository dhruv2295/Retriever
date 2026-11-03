/*
 * Copyright 2026 Google LLC
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

// EmptyState adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/EmptyState.kt (Apache-2.0). Modified for Retriever: plain strings, drawable icon, one optional action, optional artwork.

package app.retriever.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import app.retriever.core.motion.MascotMood
import app.retriever.motion.Mascot

@Composable
internal fun Muted(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
internal fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

// Adapted from google-ai-edge/gallery
// Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/videomomentfinder/ConfirmDeleteDialog.kt
// (Apache-2.0, Copyright 2026 Google LLC). Modified for Retriever: plain strings, no analytics hook,
// title and confirm label as parameters so Folders reuses it.
@Composable
fun ConfirmDialog(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { Button(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** [content] with a long-press menu that opens where the finger went down, not at the row's left edge. */
@Composable
internal fun PressMenu(
    content: @Composable (onLongClick: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    menu: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var press by remember { mutableStateOf(IntOffset.Zero) }
    // The Initial pass only watches, so the row's own click and long press still get the touch.
    Box(
        modifier.pointerInput(Unit) {
            awaitEachGesture { press = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position.round() }
        },
    ) {
        content { open = true }
        // A menu hangs from its anchor's bottom-left corner; an anchor of no size at the press puts that corner there.
        Box(Modifier.offset { press }) {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) { menu { open = false } }
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    description: String,
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    action: Pair<String, () -> Unit>? = null,
    art: Int? = null,
    mood: MascotMood? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.padding(horizontal = 48.dp),
    ) {
        if (mood != null) {
            Mascot(mood, Modifier.width(160.dp))
        } else if (art != null) {
            Image(painterResource(art), contentDescription = null, modifier = Modifier.widthIn(max = 220.dp).fillMaxWidth(0.6f))
        } else {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) Button(onClick = action.second) { Text(action.first) }
    }
}

/** Every count's bar: exactly [done] / [total], on a quiet track with no gap or end dot, so an empty bar looks empty. */
@Composable
fun CountBar(done: Long, total: Long, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { if (total > 0) done.toFloat() / total else 0f },
        modifier = modifier,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}
