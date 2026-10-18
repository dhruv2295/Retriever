package app.retriever.viewer

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.PlaybackException
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlayerViewModelTest {
    private fun handle(position: Long? = null) = SavedStateHandle(
        buildMap {
            put("uri", "content://media/clip.mp4")
            put("mime", "video/mp4")
            put("startMs", 12_000L)
            put("title", "clip.mp4")
            position?.let { put("position", it) }
        },
    )

    private fun factory(h: SavedStateHandle) = viewModelFactory {
        initializer { PlayerViewModel(ApplicationProvider.getApplicationContext(), h) }
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
        assertEquals(12_000L, PlayerViewModel(ApplicationProvider.getApplicationContext(), handle()).player.currentPosition)
    }

    @Test fun stoppingSavesThePositionAndTheRestoredViewModelOpensThere() {
        val h = handle()
        val vm = PlayerViewModel(ApplicationProvider.getApplicationContext(), h)
        vm.player.seekTo(34_000)
        vm.onStop()
        assertEquals(34_000L, h.get<Long>("position"))
        assertEquals(34_000L, PlayerViewModel(ApplicationProvider.getApplicationContext(), handle(h["position"])).player.currentPosition)
    }

    @Test fun aPlayerErrorMovesTheStateToFailed() {
        val vm = PlayerViewModel(ApplicationProvider.getApplicationContext(), handle())
        assertEquals(PlayerUi.Ready, vm.ui.value)
        vm.listener.onPlayerError(PlaybackException("gone", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
        assertEquals(PlayerUi.Failed, vm.ui.value)
    }
}
