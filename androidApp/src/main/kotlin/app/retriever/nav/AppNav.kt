package app.retriever.nav

import androidx.activity.compose.ReportDrawn
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.AnimatedVisibilityScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
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

/** Every screen the app navigates to; [Opener.open] and screens hand these to the nav host. */
sealed interface Route

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

// M3's default effects spring for the photo's shared-element transition.
private val defaultEffects = spring<Float>(1f, 1600f)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.matchesSearchPill(): Boolean {
    val from = initialState.destination
    val to = targetState.destination
    return (from.hasRoute<SearchRoute>() && (to.hasRoute<HomeRoute>() || to.hasRoute<SetupRoute>())) ||
        ((from.hasRoute<HomeRoute>() || from.hasRoute<SetupRoute>()) && to.hasRoute<SearchRoute>())
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.pageEnter(direction: SlideDirection): EnterTransition {
    val fade = fadeIn(tween(300))
    return if (matchesSearchPill()) fade else slideIntoContainer(direction, tween(300)) { it / 8 } + fade
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.pageExit(direction: SlideDirection): ExitTransition {
    val fade = fadeOut(tween(300))
    return if (matchesSearchPill()) fade else slideOutOfContainer(direction, tween(300)) { it / 8 } + fade
}

private fun predictiveDirection(edge: Int): SlideDirection = when (edge) {
    NavigationEvent.EDGE_LEFT -> SlideDirection.Right
    NavigationEvent.EDGE_RIGHT -> SlideDirection.Left
    else -> SlideDirection.End
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNav(retriever: Retriever = koinInject(), statusRequest: Int = 0, searchRequest: Pair<Int, String>? = null) {
    val koin = getKoin()
    val setUp by retriever.setUp.collectAsStateWithLifecycle()
    if (setUp == null) return
    val navController = rememberNavController()
    androidx.compose.runtime.LaunchedEffect(statusRequest) {
        if (statusRequest > 0) navController.navigate(StatusRoute) { launchSingleTop = true }
    }
    androidx.compose.runtime.LaunchedEffect(searchRequest) {
        searchRequest?.let { navController.navigate(SearchRoute(it.second, run = true)) }
    }
    // Only from a settled screen: a double tap during a transition would push twice or pop Home off the stack.
    val settled = { navController.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED }
    val go: (Route) -> Unit = { if (settled()) navController.navigate(it) }
    val back: () -> Unit = { if (settled()) navController.popBackStack() }
    // Previous and Next file: the new viewer takes this one's place, so Back still returns to the list and playback stops.
    val replace: (Route) -> Unit = { route ->
        val current = navController.currentBackStackEntry?.destination?.id
        if (settled() && current != null) navController.navigate(route) { popUpTo(current) { inclusive = true } }
    }
    // Core's machine decides; SetupFlow is built here, after setUp settles, so it seeds from the stored setup.
    val start: Route = remember { if (koin.get<SetupFlow>().showSetup) SetupRoute else HomeRoute }
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
            // A photo's tile grows into the viewer (ThumbnailImage and PhotoPage share it) over the list, which stays put
            // under the viewer's fading backdrop; Back, a predictive swipe too, shrinks it into its tile.
            val morph = @Composable { scope: AnimatedVisibilityScope -> remember(scope) { PhotoMorph(this, scope) } }
            // Ordinary pages slide and fade symmetrically; photo shared elements retain their own transition.
            // Navigation owns predictive back and system motion scale.
            NavHost(
                navController,
                startDestination = start,
                enterTransition = { pageEnter(SlideDirection.Start) },
                exitTransition = {
                    if (targetState.destination.hasRoute<PhotoRoute>()) {
                        ExitTransition.KeepUntilTransitionsFinished
                    } else {
                        pageExit(SlideDirection.Start)
                    }
                },
                popEnterTransition = {
                    if (initialState.destination.hasRoute<PhotoRoute>()) {
                        EnterTransition.None
                    } else {
                        pageEnter(SlideDirection.End)
                    }
                },
                popExitTransition = { pageExit(SlideDirection.End) },
                // A back swipe from a photo fades its backdrop as the photo follows the finger to its tile; Navigation's
                // default shrinks the whole viewer to 70%, an opaque black card until the swipe ends.
                predictivePopEnterTransition = { edge ->
                    val fromPhoto = initialState.destination.hasRoute<PhotoRoute>()
                    val direction = predictiveDirection(edge)
                    if (fromPhoto) {
                        EnterTransition.None
                    } else {
                        pageEnter(direction)
                    }
                },
                predictivePopExitTransition = { edge ->
                    val fromPhoto = initialState.destination.hasRoute<PhotoRoute>()
                    val direction = predictiveDirection(edge)
                    if (fromPhoto) {
                        fadeOut(defaultEffects)
                    } else {
                        pageExit(direction)
                    }
                },
            ) {
                composable<SetupRoute> {
                    // Setup draws its stage on the first frame: a first run's time to full display is that, not all of setup.
                    ReportDrawn()
                    // Clear the whole stack: Setup can be opened from Home ("Retriever isn't ready yet").
                    SetupScreen(onNavigate = go, pillModifier = searchBounds(this, false), onDone = {
                        navController.navigate(HomeRoute) {
                            popUpTo(navController.graph.id) { inclusive = true }
                            launchSingleTop = true
                        }
                    })
                }
                composable<HomeRoute> { HomeHost(go, pillModifier = searchBounds(this, false), morph = morph(this)) }
                composable<SearchRoute> { SearchScreen(it.toRoute<SearchRoute>(), go, back, searchBounds(this, true), morph(this)) }
                composable<BrowseRoute> { BrowseScreen(it.toRoute<BrowseRoute>(), go, back, morph = morph(this)) }
                composable<SettingsRoute> { SettingsScreen(back, onStatus = { go(StatusRoute) }) }
                composable<FoldersRoute> { FoldersScreen(go, back) }
                composable<DuplicatesRoute> { DuplicatesScreen(go, back) }
                composable<StatusRoute> { StatusScreen(back, onNavigate = go, onOpenSettings = { go(SettingsRoute) }) }
                composable<PlayerRoute> { PlayerScreen(it.toRoute<PlayerRoute>(), back, replace) }
                composable<PdfRoute> { PdfPageScreen(it.toRoute<PdfRoute>(), back, replace) }
                // Only the backdrop and bar fade: the photo itself is the tile, growing.
                composable<PhotoRoute>(
                    enterTransition = { fadeIn(defaultEffects) },
                    popExitTransition = { fadeOut(defaultEffects) },
                ) { PhotoScreen(it.toRoute<PhotoRoute>(), back, morph = morph(this)) }
                composable<DocRoute> { DocScreen(it.toRoute<DocRoute>(), back, replace) }
            }
        }
    }
}
