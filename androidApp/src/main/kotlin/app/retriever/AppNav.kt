package app.retriever

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavDestination.Companion.hasRoute
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
)

@Serializable data object FoldersRoute

@Serializable data object DuplicatesRoute

@Serializable data object StatusRoute

@Serializable data class PlayerRoute(val uri: String, val mime: String, val startMs: Long, val title: String)

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
    val go: (Any) -> Unit = { navController.navigate(it) }
    val back: () -> Unit = { navController.popBackStack() }
    val start: Any = remember { if (ready) HomeRoute else SetupRoute }
    // Set-up can be lost mid-session (the phone's AI chip failed and a new model file is needed): go back to Setup.
    LaunchedEffect(ready) {
        val here = navController.currentDestination
        if (!ready && here != null && !here.hasRoute<SetupRoute>()) {
            navController.navigate(SetupRoute) { popUpTo(navController.graph.id) { inclusive = true } }
        }
    }
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
        composable<HomeRoute> { SearchScreen(onNavigate = go) }
        composable<BrowseRoute> { BrowseScreen(it.toRoute<BrowseRoute>(), go, back) }
        composable<FoldersRoute> { FoldersScreen(go, back) }
        composable<DuplicatesRoute> { DuplicatesScreen(go, back) }
        composable<StatusRoute> { StatusScreen(back) }
        composable<PlayerRoute> { PlayerScreen(it.toRoute<PlayerRoute>(), back) }
    }
}
