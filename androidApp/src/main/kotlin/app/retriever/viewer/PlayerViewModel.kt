package app.retriever.viewer

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.toRoute
import app.retriever.nav.PlayerRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the player screen shows: the picture and controls, or a plain failure with a way out. */
sealed interface PlayerUi {
    data object Ready : PlayerUi

    data object Failed : PlayerUi
}

/**
 * Owns the one [ExoPlayer] of a player route, so a rotation keeps its item and place (it stops with the activity and
 * prepares again, paused) and the screen only attaches a view.
 * Media3 handles audio focus and the headphones-unplugged pause itself (its docs say not to write that by hand).
 */
class PlayerViewModel(context: Context, private val handle: SavedStateHandle) : ViewModel() {
    val route: PlayerRoute = handle.toRoute()

    private val _ui = MutableStateFlow<PlayerUi>(PlayerUi.Ready)
    val ui: StateFlow<PlayerUi> = _ui

    internal val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            _ui.value = PlayerUi.Failed
        }
    }

    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setAudioAttributes(AudioAttributes.DEFAULT, true)
        .setHandleAudioBecomingNoisy(true)
        .build()
        .apply {
            addListener(listener)
            setMediaItem(MediaItem.fromUri(route.uri))
            // A saved position (a recreated entry after the process died) beats where the search hit pointed.
            seekTo(handle[POSITION] ?: route.startMs)
            prepare()
            // Paused at the moment, until Play: a swipe or a step to the next file must not start sound.
            playWhenReady = false
        }

    /**
     * No background playback: leaving the app pauses and keeps the place. Stopping also gives the decoders and buffers
     * back (Media3: free them for other apps); the item and the position stay for [onStart].
     */
    fun onStop() {
        player.pause()
        handle[POSITION] = player.currentPosition
        player.stop()
    }

    /** Back in front: load the item again, still paused where it was. Not after a failure, which keeps its message. */
    fun onStart() {
        if (player.playbackState == Player.STATE_IDLE && _ui.value == PlayerUi.Ready) player.prepare()
    }

    override fun onCleared() = player.release()

    private companion object {
        const val POSITION = "position"
    }
}
