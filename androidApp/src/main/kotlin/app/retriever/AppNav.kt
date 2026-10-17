package app.retriever

import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable

@Serializable data object SetupRoute

@Serializable data object HomeRoute

@Serializable data class BrowseRoute(
    val kind: String? = null,
    val folder: String? = null,
    val sort: String = "NEWEST",
    val title: String,
    val starredOnly: Boolean = false,
)

@Serializable data object SettingsRoute

@Serializable data object FoldersRoute

@Serializable data object DuplicatesRoute

@Serializable data object StatusRoute

@Serializable data class PlayerRoute(val uri: String, val mime: String, val startMs: Long, val title: String)

@Serializable data class PdfRoute(val uri: String, val page: Int, val title: String)

/** A ViewModel built from the app's graph; reading the graph touches neither the database nor the model. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppGraph) -> VM): VM =
    viewModel(key = key, factory = viewModelFactory { initializer { create((this[APPLICATION_KEY] as RetrieverApp).graph) } })

@Composable
fun AppNav() {
    val graph = (LocalContext.current.applicationContext as RetrieverApp).graph
    val setUp by graph.retriever.setUp.collectAsStateWithLifecycle()
    val ready = setUp ?: return
    val navController = rememberNavController()
    // Only from a settled screen: a double tap during a transition would push twice or pop Home off the stack.
    val settled = { navController.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED }
    val go: (Any) -> Unit = { if (settled()) navController.navigate(it) }
    val back: () -> Unit = { if (settled()) navController.popBackStack() }
    // Names are searchable once files are allowed, so Home opens while the rest of setup carries on.
    val start: Any = remember { if (ready || graph.setup.ui.value.canSearch) HomeRoute else SetupRoute }
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
        composable<StatusRoute> { StatusScreen(back) }
        composable<PlayerRoute> { PlayerScreen(it.toRoute<PlayerRoute>(), back) }
        composable<PdfRoute> { PdfPageScreen(it.toRoute<PdfRoute>(), back) }
    }
}
