package app.rummage

import androidx.compose.runtime.Composable
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
)

@Serializable data object FoldersRoute

@Serializable data object DuplicatesRoute

@Serializable data object StatusRoute

@Serializable data class PlayerRoute(val uri: String, val mime: String, val startMs: Long, val title: String)

@Composable
fun AppNav() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val go: (Any) -> Unit = { navController.navigate(it) }
    val back: () -> Unit = { navController.popBackStack() }
    val start: Any = if ((context.applicationContext as RummageApp).graph.isSetUp()) HomeRoute else SetupRoute
    NavHost(navController, startDestination = start) {
        composable<SetupRoute> {
            // Clear the whole stack: Setup can be opened from Home ("Rummage isn't ready yet").
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
