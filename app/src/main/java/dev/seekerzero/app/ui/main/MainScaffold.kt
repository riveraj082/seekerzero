package dev.seekerzero.app.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import dev.seekerzero.app.ui.tasks.TaskComposerScreen
import dev.seekerzero.app.ui.tasks.TasksScreen
import dev.seekerzero.app.ui.terminal.TerminalScreen
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
    TabDef("tasks", R.string.tab_tasks, Icons.AutoMirrored.Outlined.Assignment),
    TabDef("terminal", R.string.tab_terminal, Icons.Outlined.Terminal)
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

    // Custom drawer state. Plain boolean — drawer opens only via the
    // hamburger callback (programmatic), closes on scrim tap / back
    // gesture / row select / delete. No gesture-based open at all, no
    // gesture competition with row buttons.
    var drawerOpen by remember { mutableStateOf(false) }
    val isChatTab = currentRoute == ROUTE_CHAT

    // Close the drawer whenever we leave the Chat tab.
    LaunchedEffect(isChatTab) {
        if (!isChatTab && drawerOpen) drawerOpen = false
    }

    // Drawer state from the data-layer singletons; no VM plumbing at this
    // level.
    val repo = remember { ChatRepository.get(context.applicationContext) }
    val contexts by repo.remoteContexts.collectAsStateWithLifecycle()
    val activeContextId by ConfigManager.activeChatContextFlow.collectAsStateWithLifecycle()
    val streaming by repo.streaming.collectAsStateWithLifecycle()

    // Refresh contexts whenever the drawer opens so A0's auto-rename of new
    // chats (which runs in an async background task during the first turn)
    // is visible next time the user looks at the list.
    LaunchedEffect(drawerOpen) {
        if (drawerOpen) repo.refreshContexts()
    }

    // Also refresh right after a reply lands, since A0 finishes the rename
    // around the same time.
    LaunchedEffect(streaming) {
        if (!streaming) {
            kotlinx.coroutines.delay(1500)
            repo.refreshContexts()
        }
    }

    // System back gesture closes the drawer.
    BackHandler(enabled = drawerOpen) { drawerOpen = false }

    Box(modifier = Modifier.fillMaxSize()) {
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
                                drawerOpen = false
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
                        ChatScreen(onMenu = { drawerOpen = true })
                    }
                    composable("approvals") { ApprovalsScreen() }
                    composable("tasks") {
                        TasksScreen(onCompose = { navController.navigate("tasks/new") })
                    }
                    composable("tasks/new") {
                        TaskComposerScreen(onBack = { navController.popBackStack() })
                    }
                    composable("terminal") { TerminalScreen() }
                }
            }
        }

        // Custom drawer overlay. Animates in/out; tapping the scrim closes.
        CustomChatDrawer(
            open = drawerOpen && isChatTab,
            onDismiss = { drawerOpen = false }
        ) {
            ChatDrawerContent(
                contexts = contexts,
                activeContextId = activeContextId,
                onSelect = { id ->
                    ConfigManager.activeChatContext = id
                    drawerOpen = false
                },
                onCreate = {
                    scope.launch {
                        repo.createContext().onSuccess { newId ->
                            ConfigManager.activeChatContext = newId
                        }
                        drawerOpen = false
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
}

/**
 * Minimal drawer overlay: scrim + sliding sheet, both animated by
 * `animateFloatAsState`. No gesture handling — the only way to open is
 * via the `open=true` parameter, and the only way to close is via the
 * scrim's click handler or external state change (BackHandler, row
 * select, etc.). This avoids all of Material 3 ModalNavigationDrawer's
 * gesture-ambiguity problems (accidental swipe-open on Chat tab, drag-
 * close eating button taps in the drawer body).
 */
@Composable
private fun CustomChatDrawer(
    open: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    val sheetWidth = 300.dp
    val density = LocalDensity.current
    val sheetWidthPx = with(density) { sheetWidth.toPx() }

    val animatedProgress by animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "drawerProgress"
    )

    if (animatedProgress <= 0f) return

    Box(modifier = Modifier.fillMaxSize()) {
        // Scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f * animatedProgress))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        )
        // Sheet, translated by (1 - progress) * width so it slides in from the left
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(sheetWidth)
                .graphicsLayer {
                    translationX = -sheetWidthPx * (1f - animatedProgress)
                }
                .background(SeekerZeroColors.Surface)
        ) {
            content()
        }
    }
}


