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

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.util.LruCache
import android.util.Size
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import app.retriever.core.Kind
import app.retriever.core.Locator
import app.retriever.core.SourceFile
import app.retriever.core.android.ImageBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

object Thumbnails {
    /**
     * The pictures, and the files with none (encrypted PDF, audio) so they are not retried on every scroll. Sized from the
     * normal heap's memory class, not largeHeap's: bitmaps live outside the Java heap, and the model and the indexer share
     * this process. Emptied once the hidden app is cached (TRIM_MEMORY_BACKGROUND), not on every brief hide (a share sheet,
     * an Open with app), which would reload every cell on the way back. Held as ImageBitmap, so a cell seeded from memory and
     * its load get the same picture, which neither recomposes the cell nor crossfades it again.
     */
    private class Memory(app: Context) : ComponentCallbacks2 {
        /** The application this cache belongs to, held weakly: a static must not hold a context. */
        val owner = WeakReference(app)
        val bitmaps: LruCache<String, ImageBitmap>
        val misses = LruCache<String, Boolean>(2000)

        init {
            val am = app.getSystemService(ActivityManager::class.java)
            val kb = am.memoryClass * 1024 / 8
            bitmaps = object : LruCache<String, ImageBitmap>(if (am.isLowRamDevice) kb / 2 else kb) {
                override fun sizeOf(key: String, value: ImageBitmap): Int = value.asAndroidBitmap().byteCount / 1024
            }
            app.registerComponentCallbacks(this)
        }

        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) clear()
        }

        @Deprecated("Superseded by onTrimMemory; still called before API 34.")
        override fun onLowMemory() = clear()

        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        private fun clear() {
            bitmaps.evictAll()
            misses.evictAll()
        }
    }

    @Volatile private var memory: Memory? = null

    /** One cache per application: the process's in use, a fresh one for each test's. */
    private fun memory(context: Context): Memory {
        val app = context.applicationContext
        return memory?.takeIf { it.owner.get() === app } ?: synchronized(this) {
            memory?.takeIf { it.owner.get() === app } ?: Memory(app).also { memory = it }
        }
    }

    /**
     * Photos, posters and provider pages come from the system's thumbnail cache. A fling asks for dozens at once: a few
     * workers keep a request from a cell that scrolled away queued, so it is dropped before it starts.
     */
    internal val THUMBNAIL_WORKERS = Runtime.getRuntime().availableProcessors().coerceIn(4, 6)

    @OptIn(ExperimentalCoroutinesApi::class)
    internal val thumbnailDispatcher = Dispatchers.IO.limitedParallelism(THUMBNAIL_WORKERS)

    /** Video frames and drawn document heads, which must not wait behind PDF pages. */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal val renderDispatcher = Dispatchers.IO.limitedParallelism(4)

    /**
     * Every PDF render in the app, the reader's pages too (PdfPageScreen). Before Android 15 every PdfRenderer call in the
     * process takes one lock, so a second PDF worker would only wait on it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal val pdfDispatcher = Dispatchers.IO.limitedParallelism(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) 4 else 1)

    internal fun dispatcherFor(kind: Kind): CoroutineDispatcher = if (kind == Kind.PDF) pdfDispatcher else thumbnailDispatcher

    private val buckets = intArrayOf(160, 256, 384, 512, 768)

    /** Request sizes come from a few buckets, so a picture loaded on Home is a cache hit in Browse. */
    fun bucket(px: Int): Int = buckets.firstOrNull { it >= px } ?: buckets.last()

    /** An edited file (new modifiedAt or size) is a new key, so neither a stale bitmap nor an old miss is reused. */
    internal fun key(file: SourceFile, sizePx: Int, locator: String = "") = "${file.uri}@${file.modifiedAt}:${file.size}@$sizePx#$locator"

    /** The bitmap when it is already in memory, so a cell can start with it and not fade in again. */
    fun cached(context: Context, file: SourceFile, sizePx: Int, locator: String = ""): ImageBitmap? = memory(context).bitmaps.get(key(file, sizePx, locator))

    /** The sharpest picture of [file] in memory at any size, for a viewer to show while it loads the file itself. */
    fun largestCached(context: Context, file: SourceFile): ImageBitmap? = buckets.reversed().firstNotNullOfOrNull { cached(context, file, it) }

    /** [locator] picks the PDF page or video moment that matched; "" shows the first page or the poster frame. */
    suspend fun load(context: Context, file: SourceFile, sizePx: Int, locator: String = ""): ImageBitmap? =
        load(context, file, sizePx, locator, dispatcherFor(file.kind)) { signal -> render(context, file, sizePx, locator, signal) }

    /** [render] runs on [dispatcher]; [Thumbnails.load] passes the real one. */
    internal suspend fun load(
        context: Context,
        file: SourceFile,
        sizePx: Int,
        locator: String,
        dispatcher: CoroutineDispatcher,
        render: suspend (CancellationSignal) -> Bitmap?,
    ): ImageBitmap? {
        val memory = memory(context)
        val key = key(file, sizePx, locator)
        memory.bitmaps.get(key)?.let { return it }
        if (memory.misses.get(key) != null) return null
        return coroutineScope {
            val signal = CancellationSignal()
            val work = async(dispatcher) {
                val bitmap = runCatching { render(signal) }.getOrNull()?.asImageBitmap()
                // Kept even when the cell has scrolled away, for when it comes back. A load cut short is not a miss.
                if (bitmap != null) {
                    memory.bitmaps.put(key, bitmap)
                } else if (isActive) {
                    memory.misses.put(key, true)
                }
                bitmap
            }
            try {
                work.await()
            } catch (e: CancellationException) {
                // await gives up at once, but the decode blocks its worker: only the signal reaches the provider.
                signal.cancel()
                throw e
            }
        }
    }

    private suspend fun render(context: Context, file: SourceFile, n: Int, locator: String, signal: CancellationSignal): Bitmap? {
        val uri = file.uri.toUri()

        // The system's thumbnail, or null; never a fallback's work for a cell that has gone.
        suspend fun system(): Bitmap? = runCatching { context.contentResolver.loadThumbnail(uri, Size(n, n), signal) }.getOrNull()
            .also { currentCoroutineContext().ensureActive() }
        return when (file.kind) {
            Kind.PHOTO, Kind.SCREENSHOT -> system() ?: ImageBytes.bitmap(context, uri, n)

            Kind.VIDEO -> {
                val ms = Locator.msOf(locator)
                // loadThumbnail ignores the time, so a moment is always cut from the video itself.
                (if (ms == null) system() else null)
                    ?: withContext(renderDispatcher) {
                        MediaMetadataRetriever().use {
                            it.setDataSource(context, uri)
                            it.getScaledFrameAtTime((ms ?: 0) * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, n, n)
                        }
                    }
            }

            Kind.PDF -> pdfPage(context, uri, (Locator.pageOf(locator) ?: 1) - 1, n)

            // A provider that draws documents (Drive, say) gives a real page; local storage has none, so the file's head is
            // drawn as one. A file with neither keeps its type card, and the miss is cached.
            Kind.DOC, Kind.OTHER -> system() ?: withContext(renderDispatcher) { DocPage.render(context, file, n) }

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
 * A file's picture in a cell: the placeholder at its final size, the picture fading in over 150 ms (instantly when the
 * system's animations are off, which Compose's motion scale already applies), and a picture that is in memory already
 * drawn at once. [longEdge] picks the request bucket, so every screen asks for the same few sizes; [locator] the PDF page
 * or video moment; [load] false keeps the icon (no access). [tint] colours the placeholder's icon.
 */
@Composable
fun ThumbnailImage(
    file: SourceFile,
    longEdge: Dp,
    modifier: Modifier = Modifier,
    load: Boolean = true,
    iconSize: Dp = 24.dp,
    container: Color = Color.Unspecified,
    locator: String = "",
    tint: Color = Color.Unspecified,
) {
    val context = LocalContext.current
    val px = Thumbnails.bucket(with(LocalDensity.current) { longEdge.roundToPx() })
    val bitmap by produceState(if (load) Thumbnails.cached(context, file, px, locator) else null, file.uri, file.modifiedAt, file.size, px, locator, load) {
        if (load) value = Thumbnails.load(context, file, px, locator)
    }
    val shown = bitmap
    // Read in the layer, not here: the cell recomposes when the fade ends, not on each of its frames.
    val fade = animateFloatAsState(if (shown != null) 1f else 0f, tween(150), label = "thumbnailFade")
    val covered by remember { derivedStateOf { fade.value >= 1f } }
    Box(modifier.background(container.takeOrElse { MaterialTheme.colorScheme.surfaceContainerHigh }), contentAlignment = Alignment.Center) {
        // A document's page leaves the cell's colour around it, so the type card underneath goes once the page is in.
        if (shown == null || !covered) KindFace(file, iconSize, tint.takeOrElse { MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) })
        if (shown != null) {
            Image(shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = fade.value })
        }
    }
}

/**
 * What a cell shows before (or instead of) a picture: a document's type card, any other kind's icon. Drawn from the
 * name alone, so it is there on the first frame; the cell's label already says "name, kind, date".
 */
@Composable
internal fun KindFace(file: SourceFile, iconSize: Dp = 24.dp, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val icon = @Composable {
        Icon(painterResource(kindIcon(file.kind)), contentDescription = null, modifier = Modifier.size(iconSize).testTag("kind-icon"), tint = tint)
    }
    if (file.kind == Kind.DOC || file.kind == Kind.OTHER) DocCard(file.name.substringAfterLast('.', "").uppercase(), icon) else icon()
}

/**
 * A document before its page is drawn ([DocPage]), or when it has none to draw (unreadable, encrypted, unknown, no
 * access): a sheet of paper with its type on it. The badge shows if it fits the page less 4 dp a side, else the kind's icon.
 */
@Composable
private fun DocCard(badge: String, icon: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val paper = RoundedCornerShape(4.dp)
    // A plain Layout, not BoxWithConstraints: a parent asking for intrinsics would break a SubcomposeLayout.
    Layout(
        contents = listOf(
            { Box(Modifier.background(colors.surfaceBright, paper).border(1.dp, colors.outlineVariant, paper)) },
            {
                // Cleared: the cell's label already names the file; the tag is for tests only.
                Box(Modifier.testTag("badge:$badge").clearAndSetSemantics {}) {
                    val style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                    Text(badge, style = style, color = colors.onSurfaceVariant, maxLines = 1, softWrap = false)
                }
            },
            icon,
        ),
    ) { (page, text, glyph), c ->
        val i = glyph.first().measure(Constraints())
        // Unbounded only while a parent asks for intrinsics: then the icon's size is the answer.
        val w = if (c.hasBoundedWidth) c.maxWidth else i.width
        val h = if (c.hasBoundedHeight) c.maxHeight else i.height
        val inset = (minOf(w, h) * 0.12f).roundToInt()
        val p = page.first().measure(Constraints.fixed((w - 2 * inset).coerceAtLeast(0), (h - 2 * inset).coerceAtLeast(0)))
        val t = text.first().measure(Constraints())
        val room = 8.dp.roundToPx()
        val shown = if (badge.isNotEmpty() && t.width <= p.width - room && t.height <= p.height - room) t else i
        layout(w, h) {
            p.place(inset, inset)
            shown.place((w - shown.width) / 2, (h - shown.height) / 2)
        }
    }
}
