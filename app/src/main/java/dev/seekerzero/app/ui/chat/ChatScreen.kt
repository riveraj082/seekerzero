package dev.seekerzero.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.seekerzero.app.R
import dev.seekerzero.app.api.models.ChatContext
import dev.seekerzero.app.ui.components.CardSurface
import dev.seekerzero.app.ui.components.SeekerZeroScaffold
import dev.seekerzero.app.ui.theme.SeekerZeroColors
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    viewModel: ChatViewModel = viewModel()
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val streaming by viewModel.streaming.collectAsStateWithLifecycle()
    val activeTool by viewModel.activeTool.collectAsStateWithLifecycle()
    val turnTools by viewModel.currentTurnTools.collectAsStateWithLifecycle()
    val contexts by viewModel.contexts.collectAsStateWithLifecycle()
    val activeContextId by viewModel.activeContextId.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        viewModel.attach()
        onDispose { viewModel.detach() }
    }

    val lastAssistantEmpty = messages.lastOrNull()?.let {
        it.role == ChatRole.ASSISTANT && it.content.isEmpty()
    } ?: false
    val showTimeline = streaming && turnTools.isNotEmpty()
    val showPill = streaming && !showTimeline && (activeTool != null || lastAssistantEmpty)

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val activeTitle = contexts.firstOrNull { it.id == activeContextId }?.displayName
        ?: stringResource(R.string.tab_chat)

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ChatDrawerContent(
                contexts = contexts,
                activeContextId = activeContextId,
                onSelect = {
                    viewModel.switchContext(it)
                    scope.launch { drawerState.close() }
                },
                onCreate = {
                    viewModel.createAndSwitch()
                    scope.launch { drawerState.close() }
                },
                onDelete = { viewModel.delete(it) },
                onRefresh = { viewModel.refreshContexts() }
            )
        }
    ) {
        SeekerZeroScaffold(
            title = activeTitle,
            onMenu = { scope.launch { drawerState.open() } }
        ) { pad ->
            Column(modifier = Modifier.fillMaxSize().padding(pad)) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (messages.isEmpty()) {
                        EmptyState()
                    } else {
                        MessageList(messages = messages)
                    }
                }
                if (showTimeline || showPill) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Text(
                            text = "Agent Zero",
                            color = SeekerZeroColors.TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp)
                        )
                        if (showTimeline) {
                            ToolTimeline(tools = turnTools)
                        } else {
                            ActivityPill(toolName = activeTool)
                        }
                    }
                }
                Composer(
                    enabled = !streaming,
                    onSend = { viewModel.send(it) }
                )
            }
        }
    }
}

@Composable
private fun ChatDrawerContent(
    contexts: List<ChatContext>,
    activeContextId: String,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onDelete: (String) -> Unit,
    onRefresh: () -> Unit
) {
    LaunchedEffect(Unit) { onRefresh() }

    ModalDrawerSheet(
        drawerContainerColor = SeekerZeroColors.Surface
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(vertical = 12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Chats",
                    color = SeekerZeroColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onCreate) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "New chat",
                        tint = SeekerZeroColors.Primary
                    )
                }
            }
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(contexts, key = { it.id }) { ctx ->
                    ContextRow(
                        context = ctx,
                        isActive = ctx.id == activeContextId,
                        onSelect = { onSelect(ctx.id) },
                        onDelete = { onDelete(ctx.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ContextRow(
    context: ChatContext,
    isActive: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val bg = if (isActive) SeekerZeroColors.SurfaceVariant else SeekerZeroColors.Surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(enabled = !isActive, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = context.displayName.ifBlank { context.id },
                color = SeekerZeroColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
            )
            if (context.id != context.displayName) {
                Text(
                    text = context.id,
                    color = SeekerZeroColors.TextDisabled,
                    fontSize = 10.sp
                )
            }
        }
        if (isActive) {
            Text(
                text = "Active",
                color = SeekerZeroColors.TextDisabled,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = "Delete chat",
                tint = SeekerZeroColors.Error
            )
        }
    }
}

@Composable
private fun ToolTimeline(tools: List<dev.seekerzero.app.chat.ToolActivity>) {
    CardSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            tools.forEachIndexed { index, t ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${index + 1}.",
                        color = SeekerZeroColors.TextDisabled,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = t.toolName,
                        color = SeekerZeroColors.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.weight(1f))
                    val durLabel = when {
                        t.inFlight -> "running…"
                        t.durationMs != null -> formatDuration(t.durationMs!!)
                        else -> ""
                    }
                    Text(
                        text = durLabel,
                        color = if (t.inFlight) SeekerZeroColors.Primary else SeekerZeroColors.TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    if (ms < 1000) return "${ms}ms"
    val s = ms / 1000.0
    return if (s < 10) "%.1fs".format(s) else "${s.toInt()}s"
}

@Composable
private fun ActivityPill(toolName: String?) {
    val label = if (toolName != null) "Using $toolName…" else "Thinking…"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(SeekerZeroColors.SurfaceVariant)
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = label,
                color = SeekerZeroColors.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun MessageList(messages: List<ChatMessage>) {
    // Hide empty in-flight assistant bubbles. The activity pill below the
    // list is already the activity indicator; showing an empty bubble with
    // "..." inside is redundant. Bubble reappears as soon as deltas land.
    val visible = messages.filterNot {
        it.role == ChatRole.ASSISTANT && !it.isFinal && it.content.isEmpty()
    }
    val listState = rememberLazyListState()

    LaunchedEffect(visible.size, visible.lastOrNull()?.content) {
        if (visible.isNotEmpty()) {
            listState.animateScrollToItem(visible.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(visible, key = { it.id }) { message ->
            MessageBubble(message = message)
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == ChatRole.USER
    val bubbleColor = if (isUser) SeekerZeroColors.SurfaceVariant else SeekerZeroColors.Surface
    val alignment = if (isUser) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Text(
            text = if (isUser) "You" else "Agent Zero",
            color = SeekerZeroColors.TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(bubbleColor)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            val displayContent = if (!message.isFinal && message.content.isEmpty()) {
                "…"
            } else if (!message.isFinal) {
                message.content + "▍"
            } else {
                message.content
            }
            Text(
                text = displayContent,
                color = SeekerZeroColors.TextPrimary,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = stringResource(R.string.chat_empty_title),
                color = SeekerZeroColors.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.chat_empty_body),
                color = SeekerZeroColors.TextSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun Composer(
    enabled: Boolean,
    onSend: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    val hint = stringResource(R.string.chat_composer_hint)
    val sendCd = stringResource(R.string.chat_send)

    CardSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 10.dp)) {
                if (text.isEmpty()) {
                    Text(
                        text = hint,
                        color = SeekerZeroColors.TextDisabled,
                        fontSize = 14.sp
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 20.dp, max = 120.dp),
                    textStyle = TextStyle(
                        color = SeekerZeroColors.TextPrimary,
                        fontSize = 14.sp
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(SeekerZeroColors.Primary)
                )
            }
            IconButton(
                onClick = {
                    val toSend = text.trim()
                    if (toSend.isNotEmpty()) {
                        onSend(toSend)
                        text = ""
                    }
                },
                enabled = enabled && text.isNotBlank()
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Send,
                    contentDescription = sendCd,
                    tint = if (enabled && text.isNotBlank())
                        SeekerZeroColors.Primary
                    else
                        SeekerZeroColors.TextDisabled
                )
            }
        }
    }
}
