/*
 * Copyright 2026 Google LLC
 * Copyright 2023 The Android Open Source Project (PdfRendererViewModel.kt, android/platform-samples)
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
// Adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/home/PhotoLibraryService.kt (Apache-2.0), bitmapLruCache and loadBitmap, and from android/platform-samples samples/graphics/pdf/src/main/java/com/example/platform/graphics/pdf/PdfRendererViewModel.kt (Apache-2.0). Modified for Retriever: keyed by uri and size, with video and PDF fallbacks.

package app.retriever.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import app.retriever.core.Kind
import app.retriever.core.Locator
import app.retriever.core.Retriever
import app.retriever.core.SourceFile
import app.retriever.core.android.ImageBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext

object Thumbnails {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** Files with no thumbnail (encrypted PDF, audio) so they are not retried on every scroll. */
    private val misses = LruCache<String, Boolean>(2000)

    // A fling would otherwise start dozens of PdfRenderer and video decodes at once. Only the slow renders queue here:
    // photos and posters come from the MediaStore thumbnail cache and must not wait behind four PDF pages.
    @OptIn(ExperimentalCoroutinesApi::class)
    internal val renderDispatcher = Dispatchers.IO.limitedParallelism(4)

    internal fun dispatcherFor(kind: Kind): CoroutineDispatcher = if (kind == Kind.PDF) renderDispatcher else Dispatchers.IO

    private val buckets = intArrayOf(256, 384, 512, 768)

    /** Request sizes come from a few buckets, so a picture loaded on Home is a cache hit in Browse. */
    fun bucket(px: Int): Int = buckets.firstOrNull { it >= px } ?: buckets.last()

    /** An edited file (new modifiedAt or size) is a new key, so neither a stale bitmap nor an old miss is reused. */
    internal fun key(file: SourceFile, sizePx: Int, locator: String = "") = "${file.uri}@${file.modifiedAt}:${file.size}@$sizePx#$locator"

    /** The bitmap when it is already in memory, so a cell can start with it and not fade in again. */
    fun cached(file: SourceFile, sizePx: Int, locator: String = ""): ImageBitmap? = cache.get(key(file, sizePx, locator))?.asImageBitmap()

    /** [locator] picks the PDF page or video moment that matched; "" shows the first page or the poster frame. */
    suspend fun load(context: Context, file: SourceFile, sizePx: Int, locator: String = ""): ImageBitmap? {
        val key = key(file, sizePx, locator)
        cached(file, sizePx, locator)?.let { return it }
        if (misses.get(key) != null) return null
        val bitmap = withContext(dispatcherFor(file.kind)) { runCatching { render(context, file, sizePx, locator) }.getOrNull() }
        // A load cancelled by scrolling away is not a miss.
        currentCoroutineContext().ensureActive()
        if (bitmap == null) misses.put(key, true)
        return bitmap?.also { cache.put(key, it) }?.asImageBitmap()
    }

    private suspend fun render(context: Context, file: SourceFile, n: Int, locator: String): Bitmap? {
        val uri = file.uri.toUri()
        return when (file.kind) {
            Kind.PHOTO, Kind.SCREENSHOT ->
                runCatching { context.contentResolver.loadThumbnail(uri, Size(n, n), null) }.getOrNull()
                    ?: ImageBytes.bitmap(context, uri, n)

            Kind.VIDEO -> {
                val ms = Locator.msOf(locator)
                // loadThumbnail ignores the time, so a moment is always cut from the video itself.
                (if (ms == null) runCatching { context.contentResolver.loadThumbnail(uri, Size(n, n), null) }.getOrNull() else null)
                    ?: withContext(renderDispatcher) {
                        MediaMetadataRetriever().use {
                            it.setDataSource(context, uri)
                            it.getScaledFrameAtTime((ms ?: 0) * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, n, n)
                        }
                    }
            }

            Kind.PDF -> pdfPage(context, uri, (Locator.pageOf(locator) ?: 1) - 1, n)

            else -> null
        }
    }

    private fun pdfPage(context: Context, uri: Uri, index: Int, n: Int): Bitmap? = context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
        PdfRenderer(fd).use { renderer ->
            renderer.openPage(index.coerceIn(0, renderer.pageCount - 1)).use { page ->
                val scale = n.toFloat() / maxOf(page.width, page.height)
                val bitmap = createBitmap(
                    (page.width * scale).toInt().coerceAtLeast(1),
                    (page.height * scale).toInt().coerceAtLeast(1),
                )
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    }
}

/**
 * A picture cell for Home's strips and mosaic: the placeholder at its final size, the picture fading in over 150 ms
 * (instantly when the system's animations are off, which Compose's motion scale already applies), and a picture that
 * is in memory already drawn at once. [longEdge] picks the request bucket; [load] false keeps the icon (no access).
 */
@Composable
fun ThumbnailImage(
    file: SourceFile,
    longEdge: Dp,
    modifier: Modifier = Modifier,
    load: Boolean = true,
    iconSize: Dp = 24.dp,
    container: Color = Color.Unspecified,
) {
    val context = LocalContext.current
    val px = Thumbnails.bucket(with(LocalDensity.current) { longEdge.roundToPx() })
    val bitmap by produceState(if (load) Thumbnails.cached(file, px) else null, file.uri, file.modifiedAt, file.size, px, load) {
        if (load) value = Thumbnails.load(context, file, px)
    }
    val shown = bitmap
    val fade by animateFloatAsState(if (shown != null) 1f else 0f, tween(150), label = "thumbnailFade")
    Box(modifier.background(container.takeOrElse { MaterialTheme.colorScheme.surfaceContainerHigh }), contentAlignment = Alignment.Center) {
        if (shown == null && (file.kind == Kind.DOC || file.kind == Kind.OTHER)) {
            TextThumbnail(file)
        } else {
            Icon(
                painterResource(kindIcon(file.kind)),
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        if (shown != null) {
            Image(shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = fade })
        }
    }
}

/** Measurement only updates this image request, leaving the keyed arrival cell untouched. */
@Composable
internal fun MeasuredThumbnailImage(file: SourceFile, modifier: Modifier = Modifier) {
    var pixels by remember { mutableIntStateOf(0) }
    val edge = with(LocalDensity.current) { pixels.coerceAtLeast(1).toDp() }
    ThumbnailImage(file, edge, modifier.onSizeChanged { pixels = Thumbnails.bucket(maxOf(it.width, it.height)) }, load = pixels > 0)
}

internal suspend fun loadTextPreview(file: SourceFile): String? = GlobalContext.getOrNull()?.getOrNull<Retriever>()?.textPreview(file)

/** Documents without pictures use their own extracted text; the enclosing card supplies the accessible file name. */
@Composable
internal fun TextThumbnail(file: SourceFile, preview: suspend (SourceFile) -> String? = ::loadTextPreview) {
    val text by produceState<String?>(null, file) {
        value = try {
            preview(file)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
    val title = MaterialTheme.typography.labelSmall
    val body = MaterialTheme.typography.bodySmall
    val titleHeight = with(LocalDensity.current) { title.lineHeight.toDp() }
    val bodyHeight = with(LocalDensity.current) { body.lineHeight.toDp() }
    BoxWithConstraints(Modifier.fillMaxSize().clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        val lines = ((maxHeight - 16.dp - titleHeight * 2) / bodyHeight).toInt().coerceAtMost(6)
        if (lines < 2) {
            Icon(painterResource(kindIcon(file.kind)), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Column(Modifier.fillMaxSize().padding(8.dp)) {
                Text(file.name, style = title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                text?.let { Text(it, style = body, maxLines = lines, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}
