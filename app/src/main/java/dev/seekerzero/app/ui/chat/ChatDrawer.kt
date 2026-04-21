package dev.seekerzero.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.seekerzero.app.api.models.ChatContext
import dev.seekerzero.app.ui.theme.SeekerZeroColors

/**
 * Drawer contents listing all mobile-* chat contexts. Rendered from
 * MainScaffold so the modal scrim covers the bottom navigation bar too —
 * previously the drawer lived inside ChatScreen and the scrim only covered
 * ChatScreen's area, letting the Chat tab icon stay tappable while the
 * drawer was open (and thus not closable by the expected gesture).
 */
@Composable
fun ChatDrawerContent(
    contexts: List<ChatContext>,
    activeContextId: String,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onDelete: (String) -> Unit,
    onRefresh: () -> Unit
) {
    LaunchedEffect(Unit) { onRefresh() }

    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SeekerZeroColors.Surface)
            .padding(top = statusBarPadding.calculateTopPadding())
            .padding(vertical = 12.dp)
    ) {
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
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
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
