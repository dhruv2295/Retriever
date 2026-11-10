// Adapted from TikTokCompose app/src/main/java/com/example/tiktokcompose/ui/composables/TikTokScreen.kt (Apache-2.0). Modified for Retriever: PlayerView attached to the route ViewModel's ExoPlayer (the Media3 Compose sample's ownership), and a top bar with back and the title.

package app.retriever.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import app.retriever.ui.components.EmptyState
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(route: PlayerRoute, onBack: () -> Unit, replace: (Route) -> Unit, modifier: Modifier = Modifier) {
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

    Scaffold(
        modifier = modifier,
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
                onRelease = { it.player = null },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(Color.Black),
            )
        }
    }
}
