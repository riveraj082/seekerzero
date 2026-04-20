package dev.seekerzero.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.seekerzero.app.config.ConfigManager
import dev.seekerzero.app.ui.main.MainScaffold
import dev.seekerzero.app.ui.setup.SetupScreen

private const val ROUTE_SETUP = "setup"
private const val ROUTE_MAIN = "main"

@Composable
fun SeekerZeroNavHost() {
    val navController = rememberNavController()
    val startDestination = remember { if (ConfigManager.isConfigured()) ROUTE_MAIN else ROUTE_SETUP }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(ROUTE_SETUP) {
            SetupScreen(onSetupComplete = {
                navController.navigate(ROUTE_MAIN) {
                    popUpTo(ROUTE_SETUP) { inclusive = true }
                }
            })
        }
        composable(ROUTE_MAIN) {
            MainScaffold()
        }
    }
}
