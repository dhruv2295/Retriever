package app.retriever.nav

import android.animation.ValueAnimator
import androidx.activity.compose.ReportDrawn
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.retriever.browse.BrowseScreen
import app.retriever.core.Retriever
import app.retriever.duplicates.DuplicatesScreen
import app.retriever.folders.FoldersScreen
import app.retriever.search.SearchScreen
import app.retriever.settings.SettingsScreen
import app.retriever.setup.SetupFlow
import app.retriever.setup.SetupScreen
import app.retriever.status.StatusScreen
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
) : Route

@Serializable data object SettingsRoute : Route

@Serializable data object FoldersRoute : Route

@Serializable data object DuplicatesRoute : Route

@Serializable data object StatusRoute : Route

@Serializable data class PlayerRoute(val uri: String, val mime: String, val startMs: Long, val title: String) : Route

/** [matched]: a search matched on [page]; false when the PDF opens at its start (from a list, or found by name). */
@Serializable data class PdfRoute(val uri: String, val page: Int, val title: String, val matched: Boolean = false) : Route

/** The route carries the opened photo, so a screen restored after process death still shows it. */
@Serializable data class PhotoRoute(val uri: String, val name: String, val mime: String) : Route

/** [fileId] 0 when the opener only has the address; the reader then looks the file up. */
@Serializable
data class DocRoute(val fileId: Long, val uri: String, val name: String, val mime: String, val locator: String = "", val query: String = "") : Route

// M3's default effects and fast effects springs (its MotionScheme is internal in material3 1.4).
private val defaultEffects = spring<Float>(1f, 1600f)
private val fastEffects = spring<Float>(1f, 3800f)

@Composable
fun AppNav(retriever: Retriever = koinInject(), statusRequest: Int = 0) {
    val koin = getKoin()
    val setUp by retriever.setUp.collectAsStateWithLifecycle()
    if (setUp == null) return
    val navController = rememberNavController()
    androidx.compose.runtime.LaunchedEffect(statusRequest) {
        if (statusRequest > 0) navController.navigate(StatusRoute) { launchSingleTop = true }
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
    // Reduce motion: setup gives way to Home with no fade or growth.
    val reduceMotion = remember { !ValueAnimator.areAnimatorsEnabled() }
    app.retriever.files.FileActionsHost(retriever) {
        NavHost(navController, startDestination = start) {
            // Setup to Home is a fade through (motion row 11): the old screen fades fast, the new one fades and grows from 92%.
            composable<SetupRoute>(exitTransition = { if (reduceMotion) ExitTransition.None else fadeOut(fastEffects) }) {
                // Setup draws its stage on the first frame: a first run's time to full display is that, not all of setup.
                ReportDrawn()
                // Clear the whole stack: Setup can be opened from Home ("Retriever isn't ready yet").
                SetupScreen(onNavigate = go, onDone = {
                    navController.navigate(HomeRoute) {
                        popUpTo(navController.graph.id) { inclusive = true }
                        launchSingleTop = true
                    }
                })
            }
            // The expanded search bar is its own window; Home must leave at once, or it covers the PDF or player while fading.
            composable<HomeRoute>(
                enterTransition = {
                    when {
                        !initialState.destination.hasRoute<SetupRoute>() -> null
                        reduceMotion -> EnterTransition.None
                        else -> fadeIn(defaultEffects) + scaleIn(defaultEffects, 0.92f)
                    }
                },
                exitTransition = {
                    val to = targetState.destination
                    if (to.hasRoute<PdfRoute>() || to.hasRoute<PhotoRoute>() || to.hasRoute<PlayerRoute>() ||
                        to.hasRoute<DocRoute>()
                    ) {
                        ExitTransition.None
                    } else {
                        null
                    }
                },
            ) { SearchScreen(onNavigate = go) }
            composable<BrowseRoute> { BrowseScreen(it.toRoute<BrowseRoute>(), go, back) }
            composable<SettingsRoute> { SettingsScreen(back, onStatus = { go(StatusRoute) }) }
            composable<FoldersRoute> { FoldersScreen(go, back) }
            composable<DuplicatesRoute> { DuplicatesScreen(go, back) }
            composable<StatusRoute> { StatusScreen(back, onNavigate = go, onOpenSettings = { go(SettingsRoute) }) }
            composable<PlayerRoute> { PlayerScreen(it.toRoute<PlayerRoute>(), back, replace) }
            composable<PdfRoute> { PdfPageScreen(it.toRoute<PdfRoute>(), back, replace) }
            composable<PhotoRoute> { PhotoScreen(it.toRoute<PhotoRoute>(), back) }
            composable<DocRoute> { DocScreen(it.toRoute<DocRoute>(), back, replace) }
        }
    }
}
