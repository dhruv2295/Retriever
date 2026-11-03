// Pinch, double-tap zoom, fling and 50 MP tiling come from saket/telephoto (Apache-2.0): Modifier.zoomable and SubSamplingImage, fed by SubSamplingImageSource.contentUriOrNull. Paging is foundation's HorizontalPager.

package app.retriever.viewer

import android.content.Context
import android.util.Size
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.core.net.toUri
import app.retriever.R
import app.retriever.core.motion.MascotMood
import app.retriever.nav.PhotoRoute
import app.retriever.starred.StarFailureEffect
import app.retriever.starred.StarHolder
import app.retriever.ui.components.EmptyState
import app.retriever.ui.components.PhotoMorph
import app.retriever.ui.components.Thumbnails
import app.retriever.ui.components.photoMorph
import app.retriever.ui.components.rememberPhotoKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.saket.telephoto.subsamplingimage.SubSamplingImage
import me.saket.telephoto.subsamplingimage.SubSamplingImageErrorReporter
import me.saket.telephoto.subsamplingimage.SubSamplingImageSource
import me.saket.telephoto.subsamplingimage.rememberSubSamplingImageState
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.io.IOException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoScreen(route: PhotoRoute, onBack: () -> Unit, modifier: Modifier = Modifier, morph: PhotoMorph? = null, queue: ViewerQueue = koinInject()) {
    val context = LocalContext.current
    val stars = koinViewModel<StarHolder>().stars
    val snackbar = remember { SnackbarHostState() }
    StarFailureEffect(stars, snackbar)
    val items = remember(route.uri) { queue.itemsFor(route) }
    val pager = rememberPagerState(items.indexOfFirst { it.uri == route.uri }.coerceAtLeast(0)) { items.size }
    val current = items[pager.currentPage]
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            ViewerTopBar(current.name, current.uri, stars, onBack, subtitle = positionLabel(pager.currentPage, items.size))
        },
    ) { padding ->
        PhotoPager(items, pager, Modifier.padding(padding), morph) { Opener.view(context, it.uri, it.mime) }
    }
}

@Composable
internal fun PhotoPager(items: List<PhotoItem>, pager: PagerState, modifier: Modifier = Modifier, morph: PhotoMorph? = null, openWith: (PhotoItem) -> Unit) {
    HorizontalPager(pager, modifier.fillMaxSize().background(Color.Black), key = { items[it].uri }, beyondViewportPageCount = 0) { page ->
        PhotoPage(items[page], current = page == pager.settledPage, morph, openWith = openWith)
    }
}

/**
 * [item]'s source for the tiles, with its sharpest picture already in memory as the preview they replace; null when the
 * photo can't be read. The decoder reports only IOExceptions; a revoked grant or a dead provider throws others, so the
 * open is tried first.
 */
internal suspend fun photoSource(context: Context, item: PhotoItem): SubSamplingImageSource? {
    val uri = item.uri.toUri()
    val readable = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }
    return if (readable) SubSamplingImageSource.contentUriOrNull(uri, preview = item.file?.let { Thumbnails.largestCached(context, it) }) else null
}

/**
 * One photo: its tile's picture from memory (a spinner without one) until the full image is sharp, then the zoomable
 * image, or a plain failure. The open page's picture is the tile's shared element: it grows from the tile into the
 * photo, cropped as the tile was and uncropped as it lands, and shrinks back into it on Back.
 */
@Composable
internal fun PhotoPage(item: PhotoItem, current: Boolean, morph: PhotoMorph?, openWith: (PhotoItem) -> Unit) {
    val context = LocalContext.current
    var failed by remember(item.uri) { mutableStateOf(false) }
    var sharp by remember(item.uri) { mutableStateOf(false) }
    val source by produceState<SubSamplingImageSource?>(null, item.uri) {
        val s = photoSource(context, item)
        if (s != null) value = s else failed = true
    }
    // The tile's picture on the first frame, so the shared element is there for the transition to match; then a
    // screen-sized one decoded while it flies, so the sharp tiles replace a picture as sharp as they are. Not cached:
    // it would push out the tile thumbnails the close lands on.
    val screenPx = LocalWindowInfo.current.containerSize.let { maxOf(it.width, it.height) }
    val preview by produceState(item.file?.let { Thumbnails.largestCached(context, it) }, item.uri) {
        withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(item.uri.toUri(), Size(screenPx, screenPx), null) }.getOrNull()
        }?.let { value = it.asImageBitmap() }
    }
    val moving = morph?.shared?.isTransitionActive == true
    if (failed) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                "Couldn't show this photo",
                "It may have been moved or deleted.",
                R.drawable.ic_warning_amber,
                action = "Open with" to { openWith(item) },
                mood = MascotMood.TROUBLE,
            )
        }
        return
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // While a picture grows or shrinks, the tiles stay hidden under it.
        source?.let {
            PhotoTiles(
                it,
                item.name,
                current,
                onFailed = { failed = true },
                onSharp = { s -> sharp = s },
                Modifier.graphicsLayer {
                    alpha =
                        if (moving) 0f else 1f
                },
            )
        }
        val shown = preview
        if (shown != null) {
            // Sized as the photo fits the screen, so the crop that matched the tile is no crop at all once it lands.
            val cover by animateFloatAsState(if (moving || !sharp) 1f else 0f, tween(150), label = "previewFade")
            Image(
                shown,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .aspectRatio(shown.width.toFloat() / shown.height)
                    .photoMorph(rememberPhotoKey(morph.takeIf { current }, item.uri))
                    .graphicsLayer { alpha = cover },
            )
        } else if (!sharp) {
            CircularProgressIndicator()
        }
    }
}

/** The zoomable photo, drawn in tiles; [onSharp] says whether it is in full quality. */
@Composable
private fun PhotoTiles(
    shown: SubSamplingImageSource,
    name: String,
    current: Boolean,
    onFailed: () -> Unit,
    onSharp: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val zoom = rememberZoomableState(ZoomSpec(maxZoomFactor = 4f))
    // A page swiped away is put back to fit, so it is not still zoomed when swiped to again.
    LaunchedEffect(current) { if (!current) zoom.resetZoom(SnapSpec()) }
    val image = rememberSubSamplingImageState(
        imageSource = shown,
        zoomableState = zoom,
        errorReporter = remember {
            object : SubSamplingImageErrorReporter {
                override fun onImageLoadingFailed(e: IOException, imageSource: SubSamplingImageSource) = onFailed()
            }
        },
    )
    LaunchedEffect(image) { snapshotFlow { image.isImageDisplayedInFullQuality }.collect(onSharp) }
    SubSamplingImage(image, contentDescription = name, modifier = modifier.fillMaxSize().zoomable(zoom))
}
