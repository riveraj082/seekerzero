package dev.seekerzero.app.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Paid
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.seekerzero.app.R
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.seekerzero.app.service.SeekerZeroService
import dev.seekerzero.app.ui.approvals.ApprovalsScreen
import dev.seekerzero.app.ui.chat.ChatScreen
import dev.seekerzero.app.ui.components.SeekerZeroScaffold
import dev.seekerzero.app.ui.theme.SeekerZeroColors

private data class TabDef(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector
)

private val TABS = listOf(
    TabDef("chat", R.string.tab_chat, Icons.AutoMirrored.Outlined.Chat),
    TabDef("approvals", R.string.tab_approvals, Icons.Outlined.Done),
    TabDef("tasks", R.string.tab_tasks, Icons.AutoMirrored.Outlined.Assignment),
    TabDef("cost", R.string.tab_cost, Icons.Outlined.Paid),
    TabDef("diagnostics", R.string.tab_diagnostics, Icons.Outlined.HealthAndSafety)
)

@Composable
fun MainScaffold() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        SeekerZeroService.start(context)
    }

    Scaffold(
        containerColor = SeekerZeroColors.Background,
        bottomBar = {
            NavigationBar(containerColor = SeekerZeroColors.Surface) {
                TABS.forEach { tab ->
                    val label = stringResource(tab.labelRes)
                    val selected = backStack?.destination?.hierarchy?.any { it.route == tab.route } == true ||
                            currentRoute == tab.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = label) },
                        label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = SeekerZeroColors.Primary,
                            selectedTextColor = SeekerZeroColors.Primary,
                            indicatorColor = SeekerZeroColors.SurfaceVariant,
                            unselectedIconColor = SeekerZeroColors.TextSecondary,
                            unselectedTextColor = SeekerZeroColors.TextSecondary
                        )
                    )
                }
            }
        }
    ) { pad ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SeekerZeroColors.Background)
                .padding(pad)
        ) {
            NavHost(navController = navController, startDestination = "chat") {
                composable("chat") { ChatScreen() }
                composable("approvals") { ApprovalsScreen() }
                composable("tasks") { TasksScreenStub() }
                composable("cost") { CostScreenStub() }
                composable("diagnostics") { DiagnosticsScreenStub() }
            }
        }
    }
}


@Composable
private fun TasksScreenStub() = TabStub(
    title = stringResource(R.string.tab_tasks),
    body = stringResource(R.string.stub_tasks_body)
)

@Composable
private fun CostScreenStub() = TabStub(
    title = stringResource(R.string.tab_cost),
    body = stringResource(R.string.stub_cost_body)
)

@Composable
private fun DiagnosticsScreenStub() = TabStub(
    title = stringResource(R.string.tab_diagnostics),
    body = stringResource(R.string.stub_diagnostics_body)
)

@Composable
private fun TabStub(title: String, body: String) {
    SeekerZeroScaffold(title = title) { pad ->
        Box(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentAlignment = Alignment.Center
        ) {
            Text(body, color = SeekerZeroColors.TextSecondary)
        }
    }
}
