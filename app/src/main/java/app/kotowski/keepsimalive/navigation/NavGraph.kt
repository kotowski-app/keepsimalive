package app.kotowski.keepsimalive.navigation

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.kotowski.keepsimalive.ui.dashboard.DashboardScreen
import app.kotowski.keepsimalive.ui.dashboard.DashboardViewModel
import app.kotowski.keepsimalive.ui.history.HistoryScreen
import app.kotowski.keepsimalive.ui.history.HistoryViewModel
import app.kotowski.keepsimalive.ui.logviewer.LogViewerScreen
import app.kotowski.keepsimalive.ui.settings.SettingsScreen
import app.kotowski.keepsimalive.ui.simsettings.SimDetailScreen
import app.kotowski.keepsimalive.ui.simsettings.SimDetailViewModel

fun NavController.safeNavigate(
    route: String,
    builder: NavOptionsBuilder.() -> Unit = {},
) {
    if (currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
        navigate(route, builder)
    }
}

fun NavController.safePopBackStack() {
    if (currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
        if (previousBackStackEntry != null) {
            popBackStack()
        }
    }
}

sealed class Screen(
    val route: String,
) {
    object Dashboard : Screen("dashboard")

    object LogViewer : Screen("log_viewer")

    object Settings : Screen("settings")

    object SimDetail : Screen("sim_detail/{simId}") {
        fun createRoute(simId: Int) = "sim_detail/$simId"
    }

    object History : Screen("history/{simId}") {
        fun createRoute(simId: Int) = "history/$simId"
    }
}

// The simId of a sim_detail/history back stack entry, or null for an invalid one.
// Subscription ids start at 1 (0 is Android's "default subscription" sentinel), so a
// missing or corrupted argument (e.g. a back stack restored from state saved without
// the argument) must not render the screen: with the 0 default it would show the
// removed-SIM error card (sim_detail) or an empty history (history) for a SIM that
// never existed.
internal fun simIdOf(arguments: Bundle?): Int? = arguments?.getInt("simId", 0)?.takeIf { it > 0 }

@Composable
fun AppNavGraph() {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = Screen.Dashboard.route,
    ) {
        composable(Screen.Dashboard.route) {
            val dashboardViewModel: DashboardViewModel = hiltViewModel()
            DashboardScreen(
                viewModel = dashboardViewModel,
                context = LocalContext.current,
                onNavigateToSettings = { navController.safeNavigate(Screen.Settings.route) },
                onSimCardClick = { simId ->
                    navController.safeNavigate(Screen.SimDetail.createRoute(simId))
                },
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.safePopBackStack() },
                onNavigateToLogViewer = { navController.safeNavigate(Screen.LogViewer.route) },
            )
        }

        composable(Screen.LogViewer.route) {
            LogViewerScreen(
                onNavigateBack = { navController.safePopBackStack() },
            )
        }

        composable(
            route = Screen.SimDetail.route,
            arguments = listOf(navArgument("simId") { type = NavType.IntType }),
        ) { backStackEntry ->
            val simId = simIdOf(backStackEntry.arguments) ?: return@composable
            val simDetailViewModel: SimDetailViewModel = hiltViewModel()
            SimDetailScreen(
                viewModel = simDetailViewModel,
                simId = simId,
                onNavigateBack = { navController.safePopBackStack() },
                onNavigateToHistory = { navController.safeNavigate(Screen.History.createRoute(simId)) },
            )
        }

        composable(
            route = Screen.History.route,
            arguments = listOf(navArgument("simId") { type = NavType.IntType }),
        ) { backStackEntry ->
            val simId = simIdOf(backStackEntry.arguments) ?: return@composable
            val historyViewModel: HistoryViewModel = hiltViewModel()
            HistoryScreen(
                viewModel = historyViewModel,
                simId = simId,
                onNavigateBack = { navController.safePopBackStack() },
            )
        }
    }
}
