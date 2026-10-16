// Adapted from TikTokCompose app/src/main/java/com/example/tiktokcompose/ui/composables/TikTokScreen.kt (Apache-2.0). Modified for Rummage: PlayerView owned by the screen with its own controller, ExoPlayer built per route and released with the view, and a top bar with back and the title.
package app.rummage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(route: PlayerRoute, onBack: () -> Unit) {
    val context = LocalContext.current
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
    val playerView = remember { PlayerView(context).apply { useController = true } }
    DisposableEffect(player) {
        playerView.player = player
        onDispose {
            playerView.player = null
            player.release()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(route.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
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
