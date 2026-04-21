package dev.seekerzero.app.chat

import android.content.Context
import dev.seekerzero.app.api.MobileApiClient
import dev.seekerzero.app.api.models.ChatMessageDto
import dev.seekerzero.app.util.LogCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Owns the chat data layer: Room-backed persistence, REST calls for send/history,
 * and the long-lived NDJSON stream. Singleton; attach/detach is reference-counted
 * so multiple attached screens share one stream.
 */
class ChatRepository private constructor(
    private val db: ChatDatabase,
    private val io: CoroutineScope
) {

    companion object {
        const val DEFAULT_CONTEXT = "mobile-seekerzero"
        private const val CACHE_CAP_PER_CONTEXT = 500
        private const val RECONNECT_DELAY_MS = 2000L
        private const val TAG = "ChatRepository"

        @Volatile
        private var INSTANCE: ChatRepository? = null

        fun get(context: Context): ChatRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ChatRepository(
                    db = ChatDatabase.get(context.applicationContext),
                    io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                ).also { INSTANCE = it }
            }
        }
    }

    private val dao = db.chatMessageDao()

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val attachMutex = Any()
    private var attachCount = 0
    private var streamJob: Job? = null

    fun messages(contextId: String = DEFAULT_CONTEXT): Flow<List<ChatMessageEntity>> =
        dao.observe(contextId)

    fun attach(contextId: String = DEFAULT_CONTEXT) {
        synchronized(attachMutex) {
            attachCount += 1
            if (attachCount == 1 && streamJob == null) {
                streamJob = io.launch { runStreamLoop(contextId) }
            }
        }
    }

    fun detach() {
        synchronized(attachMutex) {
            attachCount = (attachCount - 1).coerceAtLeast(0)
            if (attachCount == 0) {
                streamJob?.cancel()
                streamJob = null
                _connected.value = false
            }
        }
    }

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
            // Optimistically insert user + placeholder assistant so the UI updates immediately,
            // in case the stream hasn't delivered the user_msg event yet.
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

    private suspend fun runStreamLoop(contextId: String) {
        while (io.isActive) {
            try {
                // Re-fetch recent history on each (re)connect so we don't miss anything written
                // while disconnected.
                refreshHistory(contextId)
                val sinceMs = dao.maxFinalCreatedAt(contextId) ?: 0L
                _connected.value = true
                MobileApiClient.chatStream(contextId, sinceMs) { obj ->
                    handleEvent(contextId, obj)
                }
                _connected.value = false
            } catch (t: Throwable) {
                if (!io.isActive) break
                LogCollector.w(TAG, "stream ended: ${t.message}")
                _connected.value = false
                _streaming.value = false
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    private suspend fun handleEvent(contextId: String, obj: JsonObject) {
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
                _streaming.value = false
            }
            "keepalive" -> { /* no-op */ }
        }
    }

    private fun ChatMessageDto.toEntity(contextId: String) = ChatMessageEntity(
        context_id = contextId,
        id = id,
        role = role,
        content = content,
        created_at_ms = createdAtMs,
        is_final = isFinal
    )

    fun shutdown() {
        io.cancel()
        INSTANCE = null
    }
}
