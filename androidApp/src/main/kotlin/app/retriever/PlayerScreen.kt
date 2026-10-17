// Adapted from TikTokCompose app/src/main/java/com/example/tiktokcompose/ui/composables/TikTokScreen.kt (Apache-2.0). Modified for Retriever: PlayerView owned by the screen with its own controller, ExoPlayer built per route and released with the view, and a top bar with back and the title.
package app.retriever

import androidx.compose.foundation.background
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
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
    // Survives rotation, so the rebuilt player resumes where it was rather than at route.startMs.
    // A plain array, not state: updating it must not recompose the screen twice a second.
    val position = rememberSaveable(route.uri) { longArrayOf(route.startMs) }
    val player = remember(route.uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(route.uri))
            seekTo(position[0])
            prepare()
            playWhenReady = true
        }
    }
    LaunchedEffect(player) {
        while (true) {
            delay(500)
            position[0] = player.currentPosition
        }
    }
    // No background playback: leaving the app pauses, and the controls resume.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }
    // A recording has no picture: its controls stay up, or the screen is black until it is touched.
    val audio = route.mime.startsWith("audio/")
    val playerView = remember {
        PlayerView(context).apply {
            useController = true
            if (audio) {
                controllerShowTimeoutMs = 0
                controllerHideOnTouch = false
            }
        }
    }
    DisposableEffect(player) {
        playerView.player = player
        if (audio) playerView.showController()
        onDispose {
            playerView.player = null
            player.release()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(route.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (route.startMs > 0) {
                            Text("From ${Format.clock(route.startMs)}", style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                },
                actions = { StarButton(route.uri in starred) { stars.toggle(route.uri) } },
            )
        },
    ) { padding ->
        AndroidView(
            factory = { playerView },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
        )
    }
}
