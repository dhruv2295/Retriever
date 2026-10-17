package app.retriever

import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.retriever.core.Retriever
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
) : Route

@Serializable data object SettingsRoute : Route

@Serializable data object FoldersRoute : Route

@Serializable data object DuplicatesRoute : Route

@Serializable data object StatusRoute : Route

@Serializable data class PlayerRoute(val uri: String, val mime: String, val startMs: Long, val title: String) : Route

@Serializable data class PdfRoute(val uri: String, val page: Int, val title: String) : Route

@Composable
fun AppNav(retriever: Retriever = koinInject()) {
    val koin = getKoin()
    val setUp by retriever.setUp.collectAsStateWithLifecycle()
    val ready = setUp ?: return
    val navController = rememberNavController()
    // Only from a settled screen: a double tap during a transition would push twice or pop Home off the stack.
    val settled = { navController.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED }
    val go: (Route) -> Unit = { if (settled()) navController.navigate(it) }
    val back: () -> Unit = { if (settled()) navController.popBackStack() }
    // Names are searchable once files are allowed, so Home opens while the rest of setup carries on.
    // SetupFlow is built here, after setUp settles, so it seeds from the stored prefs.
    val start: Route = remember { if (ready || koin.get<SetupFlow>().ui.value.canSearch) HomeRoute else SetupRoute }
    NavHost(navController, startDestination = start) {
        composable<SetupRoute> {
            // Clear the whole stack: Setup can be opened from Home ("Retriever isn't ready yet").
            SetupScreen(onDone = {
                navController.navigate(HomeRoute) {
                    popUpTo(navController.graph.id) { inclusive = true }
                    launchSingleTop = true
                }
            })
        }
        // The expanded search bar is its own window; Home must leave at once, or it covers the PDF or player while fading.
        composable<HomeRoute>(
            exitTransition = {
                val to = targetState.destination
                if (to.hasRoute<PdfRoute>() || to.hasRoute<PlayerRoute>()) ExitTransition.None else null
            },
        ) { SearchScreen(onNavigate = go) }
        composable<BrowseRoute> { BrowseScreen(it.toRoute<BrowseRoute>(), go, back) }
        composable<SettingsRoute> { SettingsScreen(back) }
        composable<FoldersRoute> { FoldersScreen(go, back) }
        composable<DuplicatesRoute> { DuplicatesScreen(go, back) }
        composable<StatusRoute> { StatusScreen(back, onOpenSettings = { go(SettingsRoute) }) }
        composable<PlayerRoute> { PlayerScreen(it.toRoute<PlayerRoute>(), back) }
        composable<PdfRoute> { PdfPageScreen(it.toRoute<PdfRoute>(), back) }
    }
}
