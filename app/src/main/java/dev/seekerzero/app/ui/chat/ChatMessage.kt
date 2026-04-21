package dev.seekerzero.app.ui.chat

enum class ChatRole { USER, ASSISTANT }

data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val content: String,
    val createdAtMs: Long,
    val isFinal: Boolean
)
