package dev.seekerzero.app.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.seekerzero.app.R
import dev.seekerzero.app.chat.ChatRepository
import dev.seekerzero.app.config.ConfigManager
import dev.seekerzero.app.service.SeekerZeroService
import dev.seekerzero.app.ui.approvals.ApprovalsScreen
import dev.seekerzero.app.ui.chat.ChatDrawerContent
import dev.seekerzero.app.ui.chat.ChatScreen
import dev.seekerzero.app.ui.components.SeekerZeroScaffold
import dev.seekerzero.app.ui.theme.SeekerZeroColors
import kotlinx.coroutines.launch

private data class TabDef(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector
)

private const val ROUTE_CHAT = "chat"

private val TABS = listOf(
    TabDef(ROUTE_CHAT, R.string.tab_chat, Icons.AutoMirrored.Outlined.Chat),
    TabDef("approvals", R.string.tab_approvals, Icons.Outlined.Done),
    TabDef("tasks", R.string.tab_tasks, Icons.AutoMirrored.Outlined.Assignment)
)

@Composable
fun MainScaffold() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        SeekerZeroService.start(context)
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val isChatTab = currentRoute == ROUTE_CHAT

    // Close the drawer whenever we leave the Chat tab, so switching to
    // Approvals/Tasks while the drawer is open dismisses it cleanly.
    LaunchedEffect(isChatTab) {
        if (!isChatTab && drawerState.isOpen) drawerState.close()
    }

    // Drawer state is read directly from the singletons the data layer
    // already owns; no VM plumbing needed at MainScaffold level.
    val repo = remember { ChatRepository.get(context.applicationContext) }
    val contexts by repo.remoteContexts.collectAsStateWithLifecycle()
    val activeContextId by ConfigManager.activeChatContextFlow.collectAsStateWithLifecycle()
    val streaming by repo.streaming.collectAsStateWithLifecycle()

    // Refresh contexts whenever the drawer opens so A0's auto-rename of new
    // chats (which runs in an async background task during the first turn)
    // is visible next time the user looks at the list.
    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.currentValue == DrawerValue.Open) {
            repo.refreshContexts()
        }
    }

    // Also refresh right after a reply lands, since A0 finishes the
    // rename around the same time. Catches the common case of "send first
    // message on a new chat, wait for reply, reopen drawer".
    LaunchedEffect(streaming) {
        if (!streaming) {
            kotlinx.coroutines.delay(1500)
            repo.refreshContexts()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = isChatTab,
        drawerContent = {
            if (isChatTab) {
                ChatDrawerContent(
                    contexts = contexts,
                    activeContextId = activeContextId,
                    onSelect = { id ->
                        ConfigManager.activeChatContext = id
                        scope.launch { drawerState.close() }
                    },
                    onCreate = {
                        scope.launch {
                            repo.createContext().onSuccess { newId ->
                                ConfigManager.activeChatContext = newId
                            }
                            drawerState.close()
                        }
                    },
                    onDelete = { id ->
                        scope.launch {
                            val wasActive = id == activeContextId
                            repo.deleteContext(id).onSuccess {
                                if (wasActive) {
                                    val fallback = repo.remoteContexts.value
                                        .firstOrNull()?.id
                                        ?: ChatRepository.DEFAULT_CONTEXT
                                    ConfigManager.activeChatContext = fallback
                                }
                            }
                        }
                    },
                    onRefresh = { scope.launch { repo.refreshContexts() } }
                )
            }
        }
    ) {
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
                NavHost(navController = navController, startDestination = ROUTE_CHAT) {
                    composable(ROUTE_CHAT) {
                        ChatScreen(onMenu = { scope.launch { drawerState.open() } })
                    }
                    composable("approvals") { ApprovalsScreen() }
                    composable("tasks") { TasksScreenStub() }
                }
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
private fun TabStub(title: String, body: String) {
    SeekerZeroScaffold(title = title) { pad ->
        Box(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            Text(body, color = SeekerZeroColors.TextSecondary)
        }
    }
}
