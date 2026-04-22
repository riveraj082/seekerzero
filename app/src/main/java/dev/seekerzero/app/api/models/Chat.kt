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
    @SerialName("is_final") val isFinal: Boolean = true,
    val attachments: List<ChatAttachmentDto> = emptyList(),
)

@Serializable
data class ChatAttachmentDto(
    val path: String,
    val filename: String,
    val mime: String,
    val size: Long = 0L,
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
    val content: String,
    val attachments: List<String> = emptyList()
)

@Serializable
data class AttachmentUploaded(
    val path: String,
    val filename: String,
    val size: Long,
    val mime: String
)

@Serializable
data class AttachmentsUploadResponse(
    val ok: Boolean,
    val context: String,
    val attachments: List<AttachmentUploaded> = emptyList()
)

@Serializable
data class ChatSendResponse(
    val ok: Boolean,
    @SerialName("user_message_id") val userMessageId: String,
    @SerialName("assistant_message_id") val assistantMessageId: String,
    @SerialName("created_at_ms") val createdAtMs: Long
)

@Serializable
data class ChatContextCreateResponse(
    val ok: Boolean,
    val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("created_at_ms") val createdAtMs: Long
)
