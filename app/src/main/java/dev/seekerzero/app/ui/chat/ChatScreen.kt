package dev.seekerzero.app.ui.chat

import androidx.compose.foundation.background
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dev.seekerzero.app.ui.components.CardSurface
import dev.seekerzero.app.ui.components.SeekerZeroScaffold
import dev.seekerzero.app.ui.theme.SeekerZeroColors

@Composable
fun ChatScreen(
    viewModel: ChatViewModel = viewModel()
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val streaming by viewModel.streaming.collectAsStateWithLifecycle()

    SeekerZeroScaffold(title = stringResource(R.string.tab_chat)) { pad ->
        Column(modifier = Modifier.fillMaxSize().padding(pad)) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (messages.isEmpty()) {
                    EmptyState()
                } else {
                    MessageList(messages = messages)
                }
            }
            Composer(
                enabled = !streaming,
                onSend = { viewModel.send(it) }
            )
        }
    }
}

@Composable
private fun MessageList(messages: List<ChatMessage>) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, messages.lastOrNull()?.content) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(messages, key = { it.id }) { message ->
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
