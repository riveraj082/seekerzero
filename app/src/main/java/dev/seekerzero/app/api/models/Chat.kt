package dev.seekerzero.app.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatContext(
    val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("last_message_at_ms") val lastMessageAtMs: Long
)

@Serializable
data class ChatContextsResponse(
    val contexts: List<ChatContext> = emptyList(),
    @SerialName("server_time_ms") val serverTimeMs: Long
)

@Serializable
data class ChatMessageDto(
    val id: String,
    val role: String,
    val content: String,
    @SerialName("created_at_ms") val createdAtMs: Long,
    @SerialName("is_final") val isFinal: Boolean = true
)

@Serializable
data class ChatHistoryResponse(
    val context: String,
    val messages: List<ChatMessageDto> = emptyList(),
    @SerialName("server_time_ms") val serverTimeMs: Long
)

@Serializable
data class ChatSendRequest(
    val context: String,
    val content: String
)

@Serializable
data class ChatSendResponse(
    val ok: Boolean,
    @SerialName("user_message_id") val userMessageId: String,
    @SerialName("assistant_message_id") val assistantMessageId: String,
    @SerialName("created_at_ms") val createdAtMs: Long
)
