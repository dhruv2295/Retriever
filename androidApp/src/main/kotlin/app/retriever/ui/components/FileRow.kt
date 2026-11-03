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
// ThumbCell adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/smartalbum/SmartAlbumSearchScreen.kt BackgroundAssetGridTile (Apache-2.0). Modified for Retriever: loads through Thumbnails at the match, a page or time badge instead of selection and analysed states, 12 dp corners, merged TalkBack description.

package app.retriever.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.retriever.R
import app.retriever.core.Kind
import app.retriever.core.Locator
import app.retriever.core.SourceFile
import app.retriever.core.Words
import app.retriever.core.nameMatchRanges
import app.retriever.ui.Format
import app.retriever.ui.tabular

/** Strips the \u0002...\u0003 match markers and styles what they enclosed. An unclosed span runs to the end; stray or nested markers are ignored. */
fun highlighted(snippet: String, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    var open = -1
    for (c in snippet) {
        when (c) {
            '\u0002' -> if (open < 0) open = length

            '\u0003' -> if (open >= 0) {
                if (length > open) addStyle(style, open, length)
                open = -1
            }

            else -> append(c)
        }
    }
    if (open in 0 until length) addStyle(style, open, length)
}

/** The file name with the parts keyword search matched ([nameMatchRanges]) styled. Call it for a file found by its name only. */
fun nameHighlighted(name: String, query: String, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(name)
    nameMatchRanges(name, query).forEach { addStyle(style, it.first, it.last + 1) }
}

private val NAME_MATCH = SpanStyle(fontWeight = FontWeight.Bold)

private fun clickLabel(locator: String): String = Locator.pageOf(locator)?.let { "Open at page $it" }
    ?: Locator.msOf(locator)?.let { "Play from $locator" }
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
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val badge = if (trailing == null) Format.locatorLabel(locator) else null
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .combinedClickable(
                enabled = enabled,
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
        Thumb(file, 48.dp, locator = locator)
        Column(Modifier.weight(1f)) {
            val title = remember(file.name, query) { if (query != null) nameHighlighted(file.name, query, NAME_MATCH) else AnnotatedString(file.name) }
            // Two lines, cut in the middle, so a long name still ends in its extension.
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(
                subtitle ?: "${Words.kindLabel(file.kind)} · ${Format.bytes(file.size)} · ${Format.date(file.modifiedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (readNote != null) {
                Text(readNote, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (!snippet.isNullOrBlank()) {
                val text = highlighted(snippet, SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.primary))
                Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke()
        if (badge != null) {
            Surface(shape = MaterialTheme.shapes.small, color = colors.secondaryContainer, contentColor = colors.onSecondaryContainer) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (Locator.msOf(locator) != null) {
                        Icon(painterResource(R.drawable.ic_play_arrow), contentDescription = null, modifier = Modifier.size(14.dp))
                    }
                    Text(badge, style = MaterialTheme.typography.labelMedium.tabular(), maxLines = 1)
                }
            }
        }
    }
}

/** A list row's picture: [size] square, rounded, the same cache and request sizes as every other picture. */
@Composable
fun Thumb(file: SourceFile, size: Dp, modifier: Modifier = Modifier, locator: String = "") {
    val colors = MaterialTheme.colorScheme
    ThumbnailImage(
        file,
        size,
        modifier.size(size).clip(RoundedCornerShape(8.dp)),
        container = colors.surfaceContainer,
        locator = locator,
        tint = colors.onSurfaceVariant,
    )
}

/**
 * A square grid cell: the picture (at the matched page or moment) with a small badge saying where. [requestSize] is the
 * cell's width, worked out once by the grid ([adaptiveCell]), so no cell measures itself.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThumbCell(
    file: SourceFile,
    onClick: () -> Unit,
    requestSize: Dp,
    modifier: Modifier = Modifier,
    locator: String = "",
    readNote: String? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    morph: PhotoMorph? = null,
    origin: String = "",
) {
    val badge = Format.locatorLabel(locator)?.takeIf { file.kind == Kind.VIDEO || file.kind == Kind.PDF }
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            // Built when a screen reader or a test asks, not for every cell drawn.
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append("${Words.kindLabel(file.kind)}, ${file.name}, ${Format.date(file.modifiedAt)}")
                    Words.spokenPosition(locator)?.let { append(", $it") }
                    readNote?.let { append(", $it") }
                }
            }
            .combinedClickable(
                onClickLabel = clickLabel(locator),
                onLongClickLabel = onLongClickLabel,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val colors = MaterialTheme.colorScheme
        ThumbnailImage(
            file,
            requestSize,
            Modifier.fillMaxSize(),
            container = colors.surfaceContainer,
            locator = locator,
            tint = colors.onSurfaceVariant,
            morph = morph,
            origin = origin,
            shape = RoundedCornerShape(12.dp),
        )
        if (badge != null || file.kind == Kind.VIDEO) {
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
                    Icon(painterResource(R.drawable.ic_play_arrow), contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                }
                if (badge != null) Text(badge, style = MaterialTheme.typography.labelMedium.tabular(), color = Color.White, maxLines = 1)
            }
        }
    }
}

@DrawableRes
fun kindIcon(kind: Kind): Int = when (kind) {
    Kind.PHOTO -> R.drawable.ic_image
    Kind.SCREENSHOT -> R.drawable.ic_screenshot
    Kind.PDF -> R.drawable.ic_picture_as_pdf
    Kind.DOC -> R.drawable.ic_description
    Kind.AUDIO -> R.drawable.ic_audio_file
    Kind.VIDEO -> R.drawable.ic_video_file
    Kind.OTHER -> R.drawable.ic_insert_drive_file
}

/**
 * A list's contentPadding: the Scaffold's, with the bottom cleared of the navigation bar and keyboard, and [side] added
 * at both sides (see [gutter]); [includeTop] false leaves the top to the caller. Never pad the list itself, or it
 * clips instead of scrolling under the bars. The insets are read inside the lazy list's measure, not in composition,
 * so a keyboard animation re-measures the list without recomposing it.
 */
@Composable
fun contentPaddingFor(scaffold: PaddingValues, extraBottom: Dp = 16.dp, side: Dp = 0.dp, includeTop: Boolean = true): PaddingValues {
    val density = LocalDensity.current
    val nav = WindowInsets.navigationBars
    val ime = WindowInsets.ime
    return remember(scaffold, extraBottom, side, includeTop, density, nav, ime) {
        object : PaddingValues {
            override fun calculateLeftPadding(layoutDirection: LayoutDirection) = scaffold.calculateLeftPadding(layoutDirection) + side
            override fun calculateRightPadding(layoutDirection: LayoutDirection) = scaffold.calculateRightPadding(layoutDirection) + side
            override fun calculateTopPadding() = if (includeTop) scaffold.calculateTopPadding() else 0.dp
            override fun calculateBottomPadding() =
                maxOf(scaffold.calculateBottomPadding(), with(density) { maxOf(nav.getBottom(density), ime.getBottom(density)).toDp() }) + extraBottom
        }
    }
}

/** How many columns a `GridCells.Adaptive(min)` grid fits across [width]. */
fun adaptiveColumns(width: Dp, min: Dp, spacing: Dp): Int = ((width + spacing) / (min + spacing)).toInt().coerceAtLeast(1)

/**
 * The width of one column of a `GridCells.Adaptive(min)` grid with [spacing] between columns, across this box less
 * [padding] (both sides together): the grid's own rule, worked out once above it for every cell.
 */
fun BoxWithConstraintsScope.adaptiveCell(min: Dp, spacing: Dp, padding: Dp = 0.dp): Dp {
    val width = maxWidth - padding
    val columns = adaptiveColumns(width, min, spacing)
    return (width - spacing * (columns - 1)) / columns
}

/**
 * Side padding that centres a full-width list's content in a column at most [max] wide. Capping the list's own width
 * instead would leave the gutters dead to drags and wheel scrolls.
 */
fun BoxWithConstraintsScope.gutter(max: Dp = 840.dp): Dp = ((maxWidth - max) / 2).coerceAtLeast(0.dp)
