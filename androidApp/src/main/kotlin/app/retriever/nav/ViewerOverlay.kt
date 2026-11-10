package app.retriever.nav

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.OverlayScene
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import app.retriever.ui.components.PhotoMorph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/** Entry metadata for a photo or video viewer, which opens over the page it was opened from. */
const val FULL_WINDOW = "app.retriever.fullWindow"

/**
 * A photo or video opens as an overlay scene: the page under it stays composed and in place (Navigation 3 draws overlays
 * above the scenes, as it does dialogs), so closing the viewer reveals the page as it was, with nothing rebuilt.
 */
class ViewerOverlayStrategy(private val morph: PhotoMorph) : SceneStrategy<Route> {
    override fun SceneStrategyScope<Route>.calculateScene(entries: List<NavEntry<Route>>): Scene<Route>? {
        val top = entries.lastOrNull()?.takeIf { it.metadata[FULL_WINDOW] == true && entries.size > 1 } ?: return null
        return ViewerOverlay(top, entries.dropLast(1), morph, onBack)
    }
}

/** Equal for the same entries, so a recomputed scene keeps its composition. */
private class ViewerOverlay(
    private val entry: NavEntry<Route>,
    override val overlaidEntries: List<NavEntry<Route>>,
    private val morph: PhotoMorph,
    private val onBack: () -> Unit,
) : OverlayScene<Route> {
    override fun equals(other: Any?) = other is ViewerOverlay && other.entry == entry && other.overlaidEntries == overlaidEntries

    override fun hashCode() = 31 * entry.hashCode() + overlaidEntries.hashCode()

    override val key: Any = entry.contentKey
    override val entries = listOf(entry)
    override val previousEntries = overlaidEntries

    override val content: @Composable () -> Unit = {
        // The viewer owns Back while it shows: the list-detail scaffold underneath keeps its own back handler, which
        // would otherwise take the press and pop past the page.
        NavigationBackHandler(
            state = rememberNavigationEventState(currentInfo = ViewerInfo(key)),
            isBackEnabled = morph.showing(key),
            onBackCompleted = onBack,
        )
        // The black backdrop fades; the picture itself grows from its tile above it (PhotoMorph).
        // Starts hidden, so the backdrop fades in on open as well as out on close.
        val visible = remember { MutableTransitionState(false) }.apply { targetState = morph.showing(key) }
        val backdrop by rememberTransition(visible, label = "viewer").animateFloat({ spring(1f, 1600f) }, label = "viewerBackdrop") { if (it) 1f else 0f }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = backdrop }) { entry.Content() }
    }

    /** Stays until the picture has shrunk back into its tile, or the backdrop has faded when there is no tile. */
    override suspend fun onRemove() {
        val started = withTimeoutOrNull(250.milliseconds) { snapshotFlow { morph.shared.isTransitionActive }.first { it } } != null
        if (started) withTimeoutOrNull(1000.milliseconds) { snapshotFlow { morph.shared.isTransitionActive }.first { !it } }
    }
}

private data class ViewerInfo(val key: Any) : NavigationEventInfo()
