// Pinch, double-tap zoom, fling and 50 MP tiling come from saket/telephoto (Apache-2.0): Modifier.zoomable and SubSamplingImage, fed by SubSamplingImageSource.contentUriOrNull. Paging is foundation's HorizontalPager.

package app.retriever.viewer

import android.content.Context
import androidx.compose.animation.core.SnapSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import app.retriever.R
import app.retriever.core.motion.MascotMood
import app.retriever.nav.PhotoRoute
import app.retriever.starred.StarFailureEffect
import app.retriever.starred.StarHolder
import app.retriever.ui.components.EmptyState
import app.retriever.ui.components.Thumbnails
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
fun PhotoScreen(route: PhotoRoute, onBack: () -> Unit, modifier: Modifier = Modifier, queue: ViewerQueue = koinInject()) {
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
        PhotoPager(items, pager, Modifier.padding(padding)) { Opener.view(context, it.uri, it.mime) }
    }
}

@Composable
internal fun PhotoPager(items: List<PhotoItem>, pager: PagerState, modifier: Modifier = Modifier, openWith: (PhotoItem) -> Unit) {
    HorizontalPager(pager, modifier.fillMaxSize().background(Color.Black), key = { items[it].uri }, beyondViewportPageCount = 0) { page ->
        PhotoPage(items[page], current = page == pager.settledPage, openWith = openWith)
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

/** One photo: its picture from memory or a spinner until the first tiles draw, then the zoomable image, or a plain failure. */
@Composable
internal fun PhotoPage(item: PhotoItem, current: Boolean, openWith: (PhotoItem) -> Unit) {
    val context = LocalContext.current
    var failed by remember(item.uri) { mutableStateOf(false) }
    val source by produceState<SubSamplingImageSource?>(null, item.uri) {
        val s = photoSource(context, item)
        if (s != null) value = s else failed = true
    }
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
    val shown = source ?: return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    val zoom = rememberZoomableState(ZoomSpec(maxZoomFactor = 4f))
    // A page swiped away is put back to fit, so it is not still zoomed when swiped to again.
    LaunchedEffect(current) { if (!current) zoom.resetZoom(SnapSpec()) }
    val image = rememberSubSamplingImageState(
        imageSource = shown,
        zoomableState = zoom,
        errorReporter = remember {
            object : SubSamplingImageErrorReporter {
                override fun onImageLoadingFailed(e: IOException, imageSource: SubSamplingImageSource) {
                    failed =
                        true
                }
            }
        },
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        SubSamplingImage(image, contentDescription = item.name, modifier = Modifier.fillMaxSize().zoomable(zoom))
        if (!image.isImageDisplayed) CircularProgressIndicator()
    }
}
