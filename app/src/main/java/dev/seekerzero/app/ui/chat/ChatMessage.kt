package dev.seekerzero.app.ui.chat

import dev.seekerzero.app.chat.ChatAttachmentRef

enum class ChatRole { USER, ASSISTANT }

data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val content: String,
    val createdAtMs: Long,
    val isFinal: Boolean,
    val attachments: List<ChatAttachmentRef> = emptyList(),
)
