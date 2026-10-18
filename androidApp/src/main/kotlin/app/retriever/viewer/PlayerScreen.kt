// Adapted from TikTokCompose app/src/main/java/com/example/tiktokcompose/ui/composables/TikTokScreen.kt (Apache-2.0). Modified for Retriever: PlayerView attached to the route ViewModel's ExoPlayer (the Media3 Compose sample's ownership), and a top bar with back and the title.

package app.retriever.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import app.retriever.R
import app.retriever.core.Locator
import app.retriever.core.motion.MascotMood
import app.retriever.nav.PlayerRoute
import app.retriever.starred.StarButton
import app.retriever.starred.StarFailureEffect
import app.retriever.starred.StarHolder
import app.retriever.ui.components.EmptyState
import app.retriever.ui.tabular
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(route: PlayerRoute, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val stars = koinViewModel<StarHolder>().stars
    val starred by stars.starred.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    StarFailureEffect(stars, snackbar)
    val vm = koinViewModel<PlayerViewModel>()
    val ui by vm.ui.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onStop() }
    // The start time is said once, then gets out of the title's way.
    var showFrom by rememberSaveable { mutableStateOf(route.startMs > 0) }
    LaunchedEffect(Unit) {
        delay(2_000)
        showFrom = false
    }
    // A recording has no picture: its controls stay up, or the screen is black until it is touched.
    val audio = route.mime.startsWith("audio/")

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(route.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        AnimatedVisibility(showFrom) {
                            Text(
                                "From ${Locator.time((route.startMs / 1000).toInt())}",
                                style = MaterialTheme.typography.bodySmall.tabular(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                },
                actions = {
                    StarButton(route.uri in starred) { stars.toggle(route.uri) }
                    app.retriever.files.FileOverflow(route.uri, onBack)
                },
            )
        },
    ) { padding ->
        if (ui is PlayerUi.Failed) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    "Couldn't play this ${if (audio) "recording" else "video"}",
                    "It may have been moved or deleted, or this phone can't play it.",
                    R.drawable.ic_warning_amber,
                    action = "Open in another app" to { Opener.view(context, route.uri, route.mime) },
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
