package app.retriever.nav

import androidx.activity.compose.ReportDrawn
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope.ResizeMode
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.runtime.serialization.NavBackStackSerializer
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEvent
import app.retriever.browse.BrowseScreen
import app.retriever.core.Retriever
import app.retriever.duplicates.DuplicatesScreen
import app.retriever.folders.FoldersScreen
import app.retriever.search.HomeHost
import app.retriever.search.SearchScreen
import app.retriever.settings.SettingsScreen
import app.retriever.setup.SetupFlow
import app.retriever.setup.SetupScreen
import app.retriever.status.StatusScreen
import app.retriever.ui.components.PhotoMorph
import app.retriever.viewer.DocScreen
import app.retriever.viewer.PdfPageScreen
import app.retriever.viewer.PhotoScreen
import app.retriever.viewer.PlayerScreen
import kotlinx.serialization.Serializable
import org.koin.compose.getKoin
import org.koin.compose.koinInject

/** Every screen the app navigates to; [Opener.open] and screens hand these to the nav display. */
@Serializable sealed interface Route : NavKey

@Serializable data object SetupRoute : Route

@Serializable data object HomeRoute : Route

@Serializable data class BrowseRoute(
    val kind: String? = null,
    val folder: String? = null,
    val sort: String = "NEWEST",
    val title: String,
    val starredOnly: Boolean = false,
    /** The photos and videos taken from [takenFrom] to [takenTo] (ms): a year card of On this day. */
    val takenFrom: Long? = null,
    val takenTo: Long? = null,
    /** One person's photos (core's Person.id); the title follows their name, which can change there. */
    val person: Long? = null,
) : Route

/**
 * Search, opened from Home's pill or the demo's: [query] in the field, and [run] it at once (a chip, a shortcut); or
 * [photo]'s matches (the picker's address). [scoped] keeps it to the first run's files.
 */
@Serializable
data class SearchRoute(val query: String = "", val run: Boolean = false, val photo: String? = null, val scoped: Boolean = false) : Route

@Serializable data object SettingsRoute : Route

@Serializable data object FoldersRoute : Route

@Serializable data object DuplicatesRoute : Route

@Serializable data object StatusRoute : Route

@Serializable data class PlayerRoute(val uri: String, val mime: String, val startMs: Long, val title: String) : Route

/** [matched]: a search matched on [page]; false when the PDF opens at its start (from a list, or found by name). */
@Serializable data class PdfRoute(val uri: String, val page: Int, val title: String, val matched: Boolean = false, val query: String = "") : Route

/**
 * The route carries the opened photo, so a screen restored after process death still shows it. [origin] names the tile
 * list it was opened from, so it grows from that tile when the same photo is in two lists on one screen.
 */
@Serializable data class PhotoRoute(val uri: String, val name: String, val mime: String, val origin: String = "") : Route

/** [fileId] 0 when the opener only has the address; the reader then looks the file up. */
@Serializable
data class DocRoute(val fileId: Long, val uri: String, val name: String, val mime: String, val locator: String = "", val query: String = "") : Route

/**
 * The back stack's moves, made from one entry and only while it is settled (resumed): a double tap during a transition
 * would push twice or pop Home off the stack. A route already on the stack is gone back to, not pushed again (Status
 * and Settings link to each other), so no key is ever on the stack twice.
 */
private class Moves(private val stack: NavBackStack<Route>, private val lifecycle: Lifecycle) {
    private val settled get() = lifecycle.currentState == Lifecycle.State.RESUMED

    val go: (Route) -> Unit = { if (settled) stack.goTo(it) }
    val back: () -> Unit = { if (settled && stack.size > 1) stack.removeAt(stack.lastIndex) }

    /** Previous and Next file: the new viewer takes this one's place, so Back still returns to the list and playback stops. */
    val replace: (Route) -> Unit = { if (settled) stack[stack.lastIndex] = it }
}

private fun NavBackStack<Route>.goTo(route: Route) {
    val at = indexOf(route)
    if (at < 0) add(route) else while (lastIndex > at) removeAt(lastIndex)
}

// Entry tags the transitions read, since a scene holds entries, not routes.
private const val TAG = "app.retriever.tag"
private val pill = mapOf(TAG to "pill")
private val search = mapOf(TAG to "search")
private val photo = mapOf(TAG to "photo")

private fun Scene<Route>.tagged(tag: Map<String, String>) = entries.lastOrNull()?.metadata?.get(TAG) == tag[TAG]

// M3's default effects spring for the photo's shared-element transition.
private val defaultEffects = spring<Float>(1f, 1600f)

private fun AnimatedContentTransitionScope<Scene<Route>>.matchesSearchPill(): Boolean =
    (initialState.tagged(search) && targetState.tagged(pill)) || (initialState.tagged(pill) && targetState.tagged(search))

private fun AnimatedContentTransitionScope<Scene<Route>>.pageEnter(direction: SlideDirection): EnterTransition {
    val fade = fadeIn(tween(300))
    return if (matchesSearchPill()) fade else slideIntoContainer(direction, tween(300)) { it / 8 } + fade
}

private fun AnimatedContentTransitionScope<Scene<Route>>.pageExit(direction: SlideDirection): ExitTransition {
    val fade = fadeOut(tween(300))
    return if (matchesSearchPill()) fade else slideOutOfContainer(direction, tween(300)) { it / 8 } + fade
}

private fun predictiveDirection(edge: Int): SlideDirection = when (edge) {
    NavigationEvent.EDGE_LEFT -> SlideDirection.Right
    NavigationEvent.EDGE_RIGHT -> SlideDirection.Left
    else -> SlideDirection.End
}

// Ordinary pages slide and fade symmetrically. A photo grows from its tile over the list, which stays put under the
// viewer's fading backdrop; Back, a predictive swipe too, shrinks it into its tile. Navigation 3 owns predictive back and
// system motion scale.
private fun AnimatedContentTransitionScope<Scene<Route>>.push(): ContentTransform = if (targetState.tagged(photo)) {
    fadeIn(defaultEffects) togetherWith ExitTransition.KeepUntilTransitionsFinished
} else {
    pageEnter(SlideDirection.Start) togetherWith pageExit(SlideDirection.Start)
}

// A back swipe from a photo fades its backdrop as the photo follows the finger to its tile; a page's default would
// shrink the whole viewer, an opaque black card until the swipe ends.
private fun AnimatedContentTransitionScope<Scene<Route>>.pop(direction: SlideDirection): ContentTransform = if (initialState.tagged(photo)) {
    EnterTransition.None togetherWith fadeOut(defaultEffects)
} else {
    pageEnter(direction) togetherWith pageExit(direction)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNav(retriever: Retriever = koinInject(), statusRequest: Int = 0, searchRequest: Pair<Int, String>? = null) {
    val koin = getKoin()
    val setUp by retriever.setUp.collectAsStateWithLifecycle()
    if (setUp == null) return
    // Core's machine decides; SetupFlow is built here, after setUp settles, so it seeds from the stored setup.
    val stack = rememberSerializable(serializer = NavBackStackSerializer(Route.serializer())) {
        NavBackStack(if (koin.get<SetupFlow>().showSetup) SetupRoute else HomeRoute)
    }
    LaunchedEffect(statusRequest) { if (statusRequest > 0) stack.goTo(StatusRoute) }
    LaunchedEffect(searchRequest) { searchRequest?.let { stack.goTo(SearchRoute(it.second, run = true)) } }
    app.retriever.files.FileActionsHost(retriever) {
        SharedTransitionLayout {
            val searchBounds = @Composable { scope: AnimatedVisibilityScope, expanded: Boolean ->
                val contentState = rememberSharedContentState("search")
                val corner by scope.transition.animateDp(transitionSpec = { tween(300) }, label = "searchCorner") { state ->
                    val fullscreen = if (expanded) state == EnterExitState.Visible else state != EnterExitState.Visible
                    if (fullscreen) 0.dp else SearchBarDefaults.InputFieldHeight / 2
                }
                val radius = if (contentState.isMatchFound) {
                    corner
                } else if (expanded) {
                    0.dp
                } else {
                    SearchBarDefaults.InputFieldHeight / 2
                }
                val shape = RoundedCornerShape(radius)
                Modifier.sharedBounds(
                    contentState,
                    scope,
                    enter = fadeIn(tween(300)),
                    exit = fadeOut(tween(300)),
                    boundsTransform = { _, _ -> tween(300) },
                    resizeMode = ResizeMode.RemeasureToBounds,
                    clipInOverlayDuringTransition = OverlayClip(shape),
                ).clip(shape)
            }
            // A photo's tile grows into the viewer (ThumbnailImage and PhotoPage share it).
            val morph = @Composable { scope: AnimatedVisibilityScope -> remember(scope) { PhotoMorph(this, scope) } }
            val moves = @Composable {
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                remember(lifecycle) { Moves(stack, lifecycle) }
            }
            NavDisplay(
                backStack = stack,
                onBack = { if (stack.size > 1) stack.removeAt(stack.lastIndex) },
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
                sharedTransitionScope = this,
                transitionSpec = { push() },
                popTransitionSpec = { pop(SlideDirection.End) },
                predictivePopTransitionSpec = { edge -> pop(predictiveDirection(edge)) },
                entryProvider = entryProvider {
                    entry<SetupRoute>(metadata = pill) {
                        // Setup draws its stage on the first frame: a first run's time to full display is that, not all of setup.
                        ReportDrawn()
                        val scope = LocalNavAnimatedContentScope.current
                        // Clear the whole stack: Setup can be opened from Home ("Retriever isn't ready yet").
                        SetupScreen(onNavigate = moves().go, pillModifier = searchBounds(scope, false), onDone = {
                            stack.clear()
                            stack.add(HomeRoute)
                        })
                    }
                    entry<HomeRoute>(metadata = pill) {
                        val scope = LocalNavAnimatedContentScope.current
                        HomeHost(moves().go, pillModifier = searchBounds(scope, false), morph = morph(scope))
                    }
                    entry<SearchRoute>(metadata = search) { route ->
                        val scope = LocalNavAnimatedContentScope.current
                        val m = moves()
                        SearchScreen(route, m.go, m.back, searchBounds(scope, true), morph(scope))
                    }
                    entry<BrowseRoute> { route ->
                        val m = moves()
                        BrowseScreen(route, m.go, m.back, morph = morph(LocalNavAnimatedContentScope.current))
                    }
                    entry<SettingsRoute> {
                        val m = moves()
                        SettingsScreen(m.back, onStatus = { m.go(StatusRoute) })
                    }
                    entry<FoldersRoute> {
                        val m = moves()
                        FoldersScreen(m.go, m.back)
                    }
                    entry<DuplicatesRoute> {
                        val m = moves()
                        DuplicatesScreen(m.go, m.back)
                    }
                    entry<StatusRoute> {
                        val m = moves()
                        StatusScreen(m.back, onNavigate = m.go, onOpenSettings = { m.go(SettingsRoute) })
                    }
                    entry<PlayerRoute> { route ->
                        val m = moves()
                        PlayerScreen(route, m.back, m.replace)
                    }
                    entry<PdfRoute> { route ->
                        val m = moves()
                        PdfPageScreen(route, m.back, m.replace)
                    }
                    // Only the backdrop and bar fade: the photo itself is the tile, growing.
                    entry<PhotoRoute>(metadata = photo) { route ->
                        PhotoScreen(route, moves().back, morph = morph(LocalNavAnimatedContentScope.current))
                    }
                    entry<DocRoute> { route ->
                        val m = moves()
                        DocScreen(route, m.back, m.replace)
                    }
                },
            )
        }
    }
}
