package dev.seekerzero.app.chat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {

    @Query("SELECT * FROM chat_messages WHERE context_id = :contextId ORDER BY created_at_ms ASC")
    fun observe(contextId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT MAX(created_at_ms) FROM chat_messages WHERE context_id = :contextId AND is_final = 1")
    suspend fun maxFinalCreatedAt(contextId: String): Long?

    @Query("SELECT * FROM chat_messages WHERE context_id = :contextId AND id = :id LIMIT 1")
    suspend fun fetchOne(contextId: String, id: String): ChatMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<ChatMessageEntity>)

    @Query("UPDATE chat_messages SET content = :content, is_final = :isFinal, created_at_ms = :createdAtMs WHERE context_id = :contextId AND id = :id")
    suspend fun updateAssistant(contextId: String, id: String, content: String, isFinal: Boolean, createdAtMs: Long)

    @Query(
        """
        DELETE FROM chat_messages
        WHERE context_id = :contextId
          AND id NOT IN (
            SELECT id FROM chat_messages
            WHERE context_id = :contextId
            ORDER BY created_at_ms DESC
            LIMIT :keep
          )
        """
    )
    suspend fun trim(contextId: String, keep: Int)
}
