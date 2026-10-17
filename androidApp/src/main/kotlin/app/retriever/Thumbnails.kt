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

package app.retriever

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.retriever.core.Kind
import app.retriever.core.SourceFile
import app.retriever.core.android.ImageBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

object Thumbnails {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 32).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** Files with no thumbnail (encrypted PDF, audio) so they are not retried on every scroll. */
    private val misses = LruCache<String, Boolean>(2000)

    // A fling would otherwise start dozens of PdfRenderer and video decodes at once.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val renderDispatcher = Dispatchers.IO.limitedParallelism(4)

    /** [locator] picks the PDF page or video moment that matched; "" shows the first page or the poster frame. */
    suspend fun load(context: Context, file: SourceFile, sizePx: Int, locator: String = ""): ImageBitmap? {
        val key = "${file.uri}@$sizePx#$locator"
        cache.get(key)?.let { return it.asImageBitmap() }
        if (misses.get(key) != null) return null
        val bitmap = withContext(renderDispatcher) { runCatching { render(context, file, sizePx, locator) }.getOrNull() }
        if (bitmap == null) misses.put(key, true)
        return bitmap?.also { cache.put(key, it) }?.asImageBitmap()
    }

    private fun render(context: Context, file: SourceFile, n: Int, locator: String): Bitmap? {
        val uri = Uri.parse(file.uri)
        return when (file.kind) {
            Kind.PHOTO, Kind.SCREENSHOT ->
                runCatching { context.contentResolver.loadThumbnail(uri, Size(n, n), null) }.getOrNull()
                    ?: ImageBytes.bitmap(context, uri, n)
            Kind.VIDEO -> {
                val ms = Opener.locatorToMs(locator)
                // loadThumbnail ignores the time, so a moment is always cut from the video itself.
                (if (ms == null) runCatching { context.contentResolver.loadThumbnail(uri, Size(n, n), null) }.getOrNull() else null)
                    ?: MediaMetadataRetriever().use {
                        it.setDataSource(context, uri)
                        it.getScaledFrameAtTime((ms ?: 0) * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, n, n)
                    }
            }
            Kind.PDF -> pdfPage(context, uri, (Opener.locatorToPage(locator) ?: 1) - 1, n)
            else -> null
        }
    }

    private fun pdfPage(context: Context, uri: Uri, index: Int, n: Int): Bitmap? =
        context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
            PdfRenderer(fd).use { renderer ->
                renderer.openPage(index.coerceIn(0, renderer.pageCount - 1)).use { page ->
                    val scale = n.toFloat() / maxOf(page.width, page.height)
                    val bitmap = Bitmap.createBitmap(
                        (page.width * scale).toInt().coerceAtLeast(1),
                        (page.height * scale).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }
}
