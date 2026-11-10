package app.retriever.viewer

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import app.retriever.nav.PlayerRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlayerViewModelTest {
    private val route = PlayerRoute("content://media/clip.mp4", "video/mp4", 12_000L, "clip.mp4")

    private fun handle(position: Long? = null) = SavedStateHandle(buildMap { position?.let { put("position", it) } })

    private fun factory(h: SavedStateHandle) = viewModelFactory {
        initializer { PlayerViewModel(ApplicationProvider.getApplicationContext(), h, route) }
    }

    @Test fun theSameViewModelKeepsTheSamePlayerAndReleasesItWhenCleared() {
        val store = ViewModelStore()
        val provider = ViewModelProvider.create(store, factory(handle()))
        val vm = provider[PlayerViewModel::class]
        // A configuration change asks the store again: same instance, same player.
        assertSame(vm.player, provider[PlayerViewModel::class].player)
        assertFalse(vm.player.isReleased)
        store.clear()
        assertTrue(vm.player.isReleased)
    }

    @Test fun startsAtTheHitUntilAPositionWasSaved() {
        assertEquals(12_000L, PlayerViewModel(ApplicationProvider.getApplicationContext(), handle(), route).player.currentPosition)
    }

    @Test fun aSteppedToVideoOpensPausedAndATappedOnePlays() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        assertFalse(PlayerViewModel(context, handle(), route).player.playWhenReady)
        assertTrue(PlayerViewModel(context, handle(), route.copy(origin = "browse")).player.playWhenReady)
        // Restored after the process died: back where it was, paused.
        assertFalse(PlayerViewModel(context, handle(position = 3_000L), route.copy(origin = "browse")).player.playWhenReady)
    }

    @Test fun stoppingSavesThePositionAndTheRestoredViewModelOpensThere() {
        val h = handle()
        val vm = PlayerViewModel(ApplicationProvider.getApplicationContext(), h, route)
        vm.player.seekTo(34_000)
        vm.onStop()
        assertEquals(34_000L, h.get<Long>("position"))
        assertEquals(34_000L, PlayerViewModel(ApplicationProvider.getApplicationContext(), handle(h["position"]), route).player.currentPosition)
    }

    /** In the background the player holds no decoders or buffers; back in front it loads again, paused at the same place. */
    @Test fun stoppingReleasesTheMediaAndStartingLoadsItAgain() {
        val vm = PlayerViewModel(ApplicationProvider.getApplicationContext(), handle(), route)
        vm.player.seekTo(34_000)
        vm.onStop()
        assertEquals(Player.STATE_IDLE, vm.player.playbackState)
        assertEquals(34_000L, vm.player.currentPosition)
        vm.onStart()
        assertNotEquals(Player.STATE_IDLE, vm.player.playbackState)
        assertEquals(34_000L, vm.player.currentPosition)
        assertFalse(vm.player.playWhenReady)
    }

    @Test fun aPlayerErrorMovesTheStateToFailed() {
        val vm = PlayerViewModel(ApplicationProvider.getApplicationContext(), handle(), route)
        assertEquals(PlayerUi.Ready, vm.ui.value)
        vm.listener.onPlayerError(PlaybackException("gone", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
        assertEquals(PlayerUi.Failed, vm.ui.value)
    }
}
