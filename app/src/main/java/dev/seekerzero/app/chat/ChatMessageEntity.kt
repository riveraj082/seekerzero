package dev.seekerzero.app.chat

import androidx.room.Entity
import androidx.room.Index
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Entity(
    tableName = "chat_messages",
    primaryKeys = ["context_id", "id"],
    indices = [Index(value = ["context_id", "created_at_ms"])]
)
data class ChatMessageEntity(
    val context_id: String,
    val id: String,
    val role: String,
    val content: String,
    val created_at_ms: Long,
    val is_final: Boolean,
    // JSON-encoded List<ChatAttachmentRef>. Null or empty string for messages
    // with no attachments. Stored as a string so Room needs no TypeConverter.
    val attachments_json: String? = null
)

@Serializable
data class ChatAttachmentRef(
    val path: String,
    val filename: String,
    val mime: String,
    val size: Long = 0L,
)

private val attachmentsJson = Json { ignoreUnknownKeys = true }
private val attachmentsSerializer = ListSerializer(ChatAttachmentRef.serializer())

fun encodeAttachments(list: List<ChatAttachmentRef>): String? =
    if (list.isEmpty()) null else attachmentsJson.encodeToString(attachmentsSerializer, list)

fun decodeAttachments(raw: String?): List<ChatAttachmentRef> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        attachmentsJson.decodeFromString(attachmentsSerializer, raw)
    }.getOrDefault(emptyList())
}
