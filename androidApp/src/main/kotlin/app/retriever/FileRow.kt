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
// EmptyState adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/EmptyState.kt (Apache-2.0). Modified for Retriever: plain strings, ImageVector icon, one optional action, optional artwork.
// ThumbCell adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/smartalbum/SmartAlbumSearchScreen.kt BackgroundAssetGridTile (Apache-2.0). Modified for Retriever: loads through Thumbnails at the match, a page or time badge instead of selection and analysed states, 12 dp corners, merged TalkBack description.

package app.retriever

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.retriever.core.Kind
import app.retriever.core.SourceFile
import app.retriever.core.Store

/** Strips the \u0002...\u0003 match markers and styles what they enclosed. An unclosed span runs to the end; stray or nested markers are ignored. */
fun highlighted(snippet: String, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    var open = -1
    for (c in snippet) when (c) {
        '\u0002' -> if (open < 0) open = length
        '\u0003' -> if (open >= 0) {
            if (length > open) addStyle(style, open, length)
            open = -1
        }
        else -> append(c)
    }
    if (open in 0 until length) addStyle(style, open, length)
}

/**
 * The file name with each whole word the query matched shown bold, as keyword search matches: the same words, or
 * words starting with the last one when it has three letters or more. Call it for a file found by its name only.
 */
fun nameHighlighted(name: String, query: String, style: SpanStyle): AnnotatedString {
    val tokens = Store.ftsTokens(query)
    val words = tokens.toSet()
    val prefix = tokens.lastOrNull()?.takeIf { it.length >= 3 }
    if (words.isEmpty()) return AnnotatedString(name)
    return buildAnnotatedString {
        append(name)
        NAME_WORD.findAll(name).forEach { m ->
            val w = m.value.lowercase()
            if (w in words || (prefix != null && w.startsWith(prefix))) addStyle(style, m.range.first, m.range.last + 1)
        }
    }
}

private val NAME_WORD = Regex("[\\p{L}\\p{M}\\p{N}]+")

private fun clickLabel(locator: String): String =
    Opener.locatorToPage(locator)?.let { "Open at page $it" }
        ?: Opener.locatorToMs(locator)?.let { "Play from $locator" }
        ?: "Open"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    file: SourceFile,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    snippet: String? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    locator: String = "",
    query: String? = null,
    readNote: String? = null,
    onLongClickLabel: String? = null,
    onClickLabel: String = clickLabel(locator),
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val badge = if (trailing == null) Format.locatorLabel(locator) else null
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .combinedClickable(
                onClickLabel = onClickLabel,
                onLongClickLabel = onLongClickLabel,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Thumb(file, 48.dp, locator)
        Column(Modifier.weight(1f)) {
            val title = if (query != null) nameHighlighted(file.name, query, SpanStyle(fontWeight = FontWeight.Bold)) else AnnotatedString(file.name)
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle ?: "${Format.kindLabel(file.kind)} · ${Format.bytes(file.size)} · ${Format.date(file.modifiedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (readNote != null) {
                Text(readNote, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (!snippet.isNullOrBlank()) {
                val text = if ('\u0002' in snippet) {
                    highlighted(snippet, SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.primary))
                } else {
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = MaterialTheme.typography.labelMedium.fontWeight)) { append("Similar in meaning: ") }
                        append(snippet)
                    }
                }
                Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke()
        if (badge != null) {
            Surface(shape = MaterialTheme.shapes.small, color = colors.secondaryContainer, contentColor = colors.onSecondaryContainer) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (Opener.locatorToMs(locator) != null) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                    }
                    Text(badge, style = MaterialTheme.typography.labelMedium.tabular(), maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun Thumb(file: SourceFile, size: Dp, locator: String = "") {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bitmap: ImageBitmap? by produceState<ImageBitmap?>(null, file.uri, px, locator) {
        value = Thumbnails.load(context, file, px, locator)
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center,
    ) {
        val shown = bitmap
        if (shown != null) {
            Image(shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            Icon(kindIcon(file.kind), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A square grid cell: the picture (at the matched page or moment) with a small badge saying where. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThumbCell(
    file: SourceFile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    locator: String = "",
    readNote: String? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
) {
    val context = LocalContext.current
    val description = buildString {
        append("${Format.kindLabel(file.kind)}, ${file.name}, ${Format.date(file.modifiedAt)}")
        Format.locatorSpoken(locator)?.let { append(if (Opener.locatorToPage(locator) != null) ", $it" else ", at $it") }
        readNote?.let { append(", $it") }
    }
    val badge = Format.locatorLabel(locator)?.takeIf { file.kind == Kind.VIDEO || file.kind == Kind.PDF }
    BoxWithConstraints(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .combinedClickable(
                onClickLabel = clickLabel(locator),
                onLongClickLabel = onLongClickLabel,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val px = with(LocalDensity.current) { maxWidth.roundToPx() }
        val bitmap: ImageBitmap? by produceState<ImageBitmap?>(null, file.uri, px, locator) {
            value = Thumbnails.load(context, file, px, locator)
        }
        val shown = bitmap
        if (shown != null) {
            Image(shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(kindIcon(file.kind), contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (badge != null) {
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .clearAndSetSemantics {},
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (file.kind == Kind.VIDEO) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                }
                Text(badge, style = MaterialTheme.typography.labelMedium.tabular(), color = Color.White, maxLines = 1)
            }
        }
    }
}

fun kindIcon(kind: Kind): ImageVector = when (kind) {
    Kind.PHOTO -> Icons.Outlined.Image
    Kind.SCREENSHOT -> Icons.Outlined.Screenshot
    Kind.PDF -> Icons.Outlined.PictureAsPdf
    Kind.DOC -> Icons.Outlined.Description
    Kind.AUDIO -> Icons.Outlined.AudioFile
    Kind.VIDEO -> Icons.Outlined.VideoFile
    Kind.OTHER -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

@Composable
fun EmptyState(
    title: String,
    description: String,
    icon: ImageVector,
    action: Pair<String, () -> Unit>? = null,
    art: Int? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(horizontal = 48.dp),
    ) {
        if (art != null) {
            Image(painterResource(art), contentDescription = null, modifier = Modifier.widthIn(max = 220.dp).fillMaxWidth(0.6f))
        } else {
            Icon(icon, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) Button(onClick = action.second) { Text(action.first) }
    }
}

/**
 * A list's contentPadding: the Scaffold's, with the bottom cleared of the navigation bar and keyboard, and [side] added
 * at both sides (see [gutter]). Never pad the list itself, or it clips instead of scrolling under the bars.
 */
@Composable
fun contentPaddingFor(scaffold: PaddingValues, extraBottom: Dp = 16.dp, side: Dp = 0.dp): PaddingValues {
    val direction = LocalLayoutDirection.current
    val bars = WindowInsets.navigationBars.union(WindowInsets.ime).asPaddingValues().calculateBottomPadding()
    return PaddingValues(
        start = scaffold.calculateStartPadding(direction) + side,
        top = scaffold.calculateTopPadding(),
        end = scaffold.calculateEndPadding(direction) + side,
        bottom = maxOf(scaffold.calculateBottomPadding(), bars) + extraBottom,
    )
}

/**
 * Side padding that centres a full-width list's content in a column at most [max] wide. Capping the list's own width
 * instead would leave the gutters dead to drags and wheel scrolls.
 */
fun BoxWithConstraintsScope.gutter(max: Dp = 840.dp): Dp = ((maxWidth - max) / 2).coerceAtLeast(0.dp)
