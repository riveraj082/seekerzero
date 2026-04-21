package dev.seekerzero.app.chat

import androidx.room.Entity
import androidx.room.Index

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
    val is_final: Boolean
)
