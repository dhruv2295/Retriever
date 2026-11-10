// Adapted from TikTokCompose app/src/main/java/com/example/tiktokcompose/ui/composables/TikTokScreen.kt (Apache-2.0). Modified for Retriever: PlayerView attached to the route ViewModel's ExoPlayer (the Media3 Compose sample's ownership), and a top bar with back and the title.

package app.retriever.viewer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import app.retriever.R
import app.retriever.core.Locator
import app.retriever.core.Words
import app.retriever.core.motion.MascotMood
import app.retriever.nav.PlayerRoute
import app.retriever.nav.Route
import app.retriever.starred.StarFailureEffect
import app.retriever.starred.StarHolder
import app.retriever.ui.RetrieverTheme
import app.retriever.ui.components.EmptyState
import app.retriever.ui.components.PhotoMorph
import app.retriever.ui.components.Thumbnails
import app.retriever.ui.components.photoMorph
import app.retriever.ui.components.rememberPhotoKey
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    route: PlayerRoute,
    onBack: () -> Unit,
    replace: (Route) -> Unit,
    modifier: Modifier = Modifier,
    morph: PhotoMorph? = null,
    queue: ViewerQueue = koinInject(),
) {
    val context = LocalContext.current
    val stars = koinViewModel<StarHolder>().stars
    val snackbar = remember { SnackbarHostState() }
    StarFailureEffect(stars, snackbar)
    val vm = koinViewModel<PlayerViewModel> { parametersOf(route) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.onStart() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onStop() }
    // A recording has no picture: its controls stay up, or the screen is black until it is touched.
    val audio = route.mime.startsWith("audio/")
    // As a photo: the tile's poster from memory on the first frame grows into place (and shrinks back on Back), and the
    // video takes over once it is in place and its first frame is drawn.
    val poster = remember(route.uri) { queue.file(route.uri)?.let { Thumbnails.largestCached(context, it) } }
    val moving = morph?.shared?.isTransitionActive == true
    var drawn by remember { mutableStateOf(false) }
    DisposableEffect(vm.player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                drawn = true
            }
        }
        vm.player.addListener(listener)
        onDispose { vm.player.removeListener(listener) }
    }

    RetrieverTheme(dark = true) {
        Scaffold(
            modifier = modifier,
            containerColor = Color.Black,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                ViewerTopBar(
                    route.title,
                    route.uri,
                    stars,
                    onBack,
                    subtitle = if (route.startMs > 0) Words.strings.matchAt(Locator.time((route.startMs / 1000).toInt())) else null,
                )
            },
            bottomBar = { ViewerStepBar(route.uri, replace) },
        ) { padding ->
            if (ui is PlayerUi.Failed) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    EmptyState(
                        Words.strings.couldntPlay(audio),
                        Words.strings.maybeMovedOrCantPlay,
                        R.drawable.ic_warning_amber,
                        action = Words.strings.openInAnotherApp to { Opener.view(context, route.uri, route.mime) },
                        mood = MascotMood.TROUBLE,
                    )
                }
            } else {
                Box(Modifier.fillMaxSize().padding(padding).background(Color.Black), contentAlignment = Alignment.Center) {
                    // The player's surface can't fade or move with the transition, so it waits until the poster has landed.
                    if (!moving) {
                        AndroidView(
                            factory = { c ->
                                PlayerView(c).apply {
                                    useController = true
                                    if (audio) {
                                        controllerShowTimeoutMs = 0
                                        controllerHideOnTouch = false
                                    }
                                    player = vm.player
                                    if (audio) showController()
                                }
                            },
                            onRelease = {
                                it.player = null
                                drawn = false
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (poster != null && (moving || !drawn)) {
                        Image(
                            poster,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.aspectRatio(
                                poster.width.toFloat() / poster.height,
                            ).photoMorph(rememberPhotoKey(morph, route.origin, route.uri), inViewer = true),
                        )
                    }
                }
            }
        }
    }
}
