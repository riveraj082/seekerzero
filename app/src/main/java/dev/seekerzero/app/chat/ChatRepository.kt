package dev.seekerzero.app.chat

import android.content.Context
import dev.seekerzero.app.api.MobileApiClient
import dev.seekerzero.app.api.models.ChatContext
import dev.seekerzero.app.api.models.ChatMessageDto
import dev.seekerzero.app.util.LogCollector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * An in-session record of one tool invocation during the active assistant
 * turn. Not persisted: once the final event arrives or the turn is
 * abandoned, the list is cleared. Shown inline under the in-flight
 * assistant bubble as a compact timeline.
 */
data class ToolActivity(
    val id: String,
    val toolName: String,
    val startedAtMs: Long,
    val endedAtMs: Long? = null
) {
    val durationMs: Long? get() = endedAtMs?.let { it - startedAtMs }
    val inFlight: Boolean get() = endedAtMs == null
}

/**
 * Data layer for chat. Owns the Room cache and the REST calls for send/history.
 * Does NOT own the NDJSON stream — that lives in SeekerZeroService and routes
 * events in via [applyEvent].
 */
class ChatRepository private constructor(
    private val db: ChatDatabase
) {

    companion object {
        const val DEFAULT_CONTEXT = "mobile-seekerzero"
        private const val CACHE_CAP_PER_CONTEXT = 500
        private const val TAG = "ChatRepository"

        @Volatile
        private var INSTANCE: ChatRepository? = null

        fun get(context: Context): ChatRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ChatRepository(
                    db = ChatDatabase.get(context.applicationContext)
                ).also { INSTANCE = it }
            }
        }
    }

    private val dao = db.chatMessageDao()

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    private val _activeTool = MutableStateFlow<String?>(null)
    val activeTool: StateFlow<String?> = _activeTool.asStateFlow()

    private val _currentTurnTools = MutableStateFlow<List<ToolActivity>>(emptyList())
    val currentTurnTools: StateFlow<List<ToolActivity>> = _currentTurnTools.asStateFlow()

    private val _remoteContexts = MutableStateFlow<List<ChatContext>>(emptyList())
    val remoteContexts: StateFlow<List<ChatContext>> = _remoteContexts.asStateFlow()

    fun messages(contextId: String = DEFAULT_CONTEXT): Flow<List<ChatMessageEntity>> =
        dao.observe(contextId)

    suspend fun maxFinalMs(contextId: String = DEFAULT_CONTEXT): Long =
        dao.maxFinalCreatedAt(contextId) ?: 0L

    suspend fun refreshHistory(contextId: String = DEFAULT_CONTEXT, limit: Int = 50) {
        MobileApiClient.chatHistory(contextId, limit = limit)
            .onSuccess { resp ->
                val rows = resp.messages.map { it.toEntity(contextId) }
                if (rows.isNotEmpty()) {
                    dao.upsertAll(rows)
                    dao.trim(contextId, CACHE_CAP_PER_CONTEXT)
                }
            }
            .onFailure { LogCollector.w(TAG, "refreshHistory failed: ${it.message}") }
    }

    suspend fun send(
        contextId: String = DEFAULT_CONTEXT,
        content: String
    ): Result<String> {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("empty content"))

        val result = MobileApiClient.chatSend(contextId, trimmed)
        result.onSuccess { resp ->
            // Optimistic inserts so the UI updates before the stream delivers the events.
            dao.upsert(
                ChatMessageEntity(
                    context_id = contextId,
                    id = resp.userMessageId,
                    role = "user",
                    content = trimmed,
                    created_at_ms = resp.createdAtMs,
                    is_final = true
                )
            )
            dao.upsert(
                ChatMessageEntity(
                    context_id = contextId,
                    id = resp.assistantMessageId,
                    role = "assistant",
                    content = "",
                    created_at_ms = resp.createdAtMs + 1,
                    is_final = false
                )
            )
            _streaming.value = true
        }
        return result.map { it.assistantMessageId }
    }

    /**
     * Route an NDJSON event from the service's stream into Room.
     * No-op for unknown types (including `keepalive`).
     */
    suspend fun applyEvent(contextId: String, obj: JsonObject) {
        val type = obj["type"]?.jsonPrimitive?.content ?: return
        when (type) {
            "user_msg" -> {
                val id = obj["message_id"]?.jsonPrimitive?.content ?: return
                val content = obj["content"]?.jsonPrimitive?.content.orEmpty()
                val createdAt = obj["created_at_ms"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: System.currentTimeMillis()
                dao.upsert(
                    ChatMessageEntity(
                        context_id = contextId,
                        id = id,
                        role = "user",
                        content = content,
                        created_at_ms = createdAt,
                        is_final = true
                    )
                )
            }
            "delta" -> {
                val id = obj["message_id"]?.jsonPrimitive?.content ?: return
                val delta = obj["delta"]?.jsonPrimitive?.content.orEmpty()
                if (delta.isEmpty()) return
                val existing = dao.fetchOne(contextId, id)
                val newContent = (existing?.content ?: "") + delta
                val createdAt = existing?.created_at_ms ?: System.currentTimeMillis()
                dao.upsert(
                    ChatMessageEntity(
                        context_id = contextId,
                        id = id,
                        role = "assistant",
                        content = newContent,
                        created_at_ms = createdAt,
                        is_final = false
                    )
                )
                _streaming.value = true
            }
            "final" -> {
                val id = obj["message_id"]?.jsonPrimitive?.content ?: return
                val content = obj["content"]?.jsonPrimitive?.content.orEmpty()
                val createdAt = obj["created_at_ms"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: System.currentTimeMillis()
                dao.upsert(
                    ChatMessageEntity(
                        context_id = contextId,
                        id = id,
                        role = "assistant",
                        content = content,
                        created_at_ms = createdAt,
                        is_final = true
                    )
                )
                dao.trim(contextId, CACHE_CAP_PER_CONTEXT)
                _activeTool.value = null
                _currentTurnTools.value = emptyList()
                _streaming.value = false
            }
            "tool_call" -> {
                val toolName = obj["tool_name"]?.jsonPrimitive?.content ?: return
                val startedAt = obj["created_at_ms"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: System.currentTimeMillis()
                _currentTurnTools.value = _currentTurnTools.value + ToolActivity(
                    id = UUID.randomUUID().toString(),
                    toolName = toolName,
                    startedAtMs = startedAt
                )
                _activeTool.value = toolName
                _streaming.value = true
            }
            "tool_result" -> {
                val toolName = obj["tool_name"]?.jsonPrimitive?.content
                val endedAt = obj["created_at_ms"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: System.currentTimeMillis()
                val list = _currentTurnTools.value
                val idx = list.indexOfLast {
                    it.endedAtMs == null && (toolName == null || it.toolName == toolName)
                }
                if (idx >= 0) {
                    _currentTurnTools.value = list.toMutableList().also {
                        it[idx] = it[idx].copy(endedAtMs = endedAt)
                    }
                }
                _activeTool.value = null
            }
        }
    }

    fun markStreamingIdle() {
        _streaming.value = false
        _activeTool.value = null
        _currentTurnTools.value = emptyList()
    }

    suspend fun refreshContexts(): Result<List<ChatContext>> {
        val result = MobileApiClient.chatContexts()
        result.onSuccess { _remoteContexts.value = it.contexts }
        return result.map { it.contexts }
    }

    suspend fun createContext(): Result<String> {
        val result = MobileApiClient.chatContextCreate()
        result.onSuccess {
            // Optimistically prepend to the list so the drawer updates immediately.
            val entry = ChatContext(
                id = it.id,
                displayName = it.displayName,
                lastMessageAtMs = it.createdAtMs
            )
            _remoteContexts.value = listOf(entry) + _remoteContexts.value.filterNot { c -> c.id == it.id }
        }
        return result.map { it.id }
    }

    suspend fun deleteContext(contextId: String): Result<Unit> {
        val result = MobileApiClient.chatContextDelete(contextId)
        result.onSuccess {
            _remoteContexts.value = _remoteContexts.value.filterNot { it.id == contextId }
            // Wipe the Room cache for that context so a later switch-back
            // doesn't show ghost rows.
            runCatching { dao.trim(contextId, 0) }
        }
        return result
    }

    private fun ChatMessageDto.toEntity(contextId: String) = ChatMessageEntity(
        context_id = contextId,
        id = id,
        role = role,
        content = content,
        created_at_ms = createdAtMs,
        is_final = isFinal
    )
}
