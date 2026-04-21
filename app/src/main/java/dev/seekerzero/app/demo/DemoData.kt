package dev.seekerzero.app.demo

import dev.seekerzero.app.api.models.ChatContext
import dev.seekerzero.app.api.models.ChatContextCreateResponse
import dev.seekerzero.app.api.models.ChatContextsResponse
import dev.seekerzero.app.api.models.ChatHistoryResponse
import dev.seekerzero.app.api.models.ChatMessageDto
import dev.seekerzero.app.api.models.ChatSendResponse
import dev.seekerzero.app.api.models.ErroredTask
import dev.seekerzero.app.api.models.HealthResponse
import dev.seekerzero.app.api.models.SubordinateStatus
import dev.seekerzero.app.api.models.TaskActionResponse
import dev.seekerzero.app.api.models.TaskCreateResponse
import dev.seekerzero.app.api.models.TaskDto
import dev.seekerzero.app.api.models.TaskSchedule
import dev.seekerzero.app.api.models.TasksListResponse
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Fake data + helpers for demo mode. When ConfigManager.demoMode is true,
 * MobileApiClient and SshClient short-circuit their real network paths
 * and pull from this module. Purely for video presentations — never for
 * real use.
 */
object DemoData {

    private fun now() = System.currentTimeMillis()

    // ---------- Chat contexts ---------------------------------------------

    private val contexts = mutableListOf(
        ChatContext(
            id = "mobile-seekerzero",
            displayName = "Solana Seeker overview",
            lastMessageAtMs = now() - 60_000
        ),
        ChatContext(
            id = "mobile-demo2",
            displayName = "Research — DLMM positions",
            lastMessageAtMs = now() - 300_000
        ),
        ChatContext(
            id = "mobile-demo3",
            displayName = "Daily digest drafts",
            lastMessageAtMs = now() - 3_600_000
        )
    )

    fun chatContexts() = ChatContextsResponse(
        contexts = contexts.toList(),
        serverTimeMs = now()
    )

    fun createContext(): ChatContextCreateResponse {
        val id = "mobile-${UUID.randomUUID().toString().take(8)}"
        val c = ChatContext(id = id, displayName = "New chat", lastMessageAtMs = now())
        contexts.add(0, c)
        return ChatContextCreateResponse(
            ok = true, id = id, displayName = c.displayName, createdAtMs = now()
        )
    }

    fun deleteContext(id: String) {
        contexts.removeAll { it.id == id }
    }

    // ---------- Chat history (per context) --------------------------------

    private val seedHistory: Map<String, List<ChatMessageDto>> = mapOf(
        "mobile-seekerzero" to listOf(
            ChatMessageDto(
                id = "msg-u-seed1", role = "user",
                content = "What's the Solana Seeker phone?",
                createdAtMs = now() - 120_000, isFinal = true
            ),
            ChatMessageDto(
                id = "msg-a-seed1", role = "assistant",
                content = "The Solana Seeker is a crypto-native Android phone from Solana Mobile. It has hardware-isolated seed-vault key storage, a Web3 dApp store, and native Solana Pay integration. Successor to the Saga launched mid-2024.",
                createdAtMs = now() - 118_000, isFinal = true
            )
        ),
        "mobile-demo2" to listOf(
            ChatMessageDto(
                id = "msg-u-seed2", role = "user",
                content = "Summarize my active DLMM positions.",
                createdAtMs = now() - 350_000, isFinal = true
            ),
            ChatMessageDto(
                id = "msg-a-seed2", role = "assistant",
                content = "You have 3 active DLMM positions on Meteora. Total deposited: \$4,820. Current unrealized PnL: +\$312 (+6.5%). Highest-yielding position: SOL-USDC 0.05% bin step, earning 12.3% APY.",
                createdAtMs = now() - 348_000, isFinal = true
            )
        ),
        "mobile-demo3" to emptyList()
    )

    fun chatHistory(contextId: String): ChatHistoryResponse {
        return ChatHistoryResponse(
            context = contextId,
            messages = seedHistory[contextId] ?: emptyList(),
            serverTimeMs = now()
        )
    }

    fun chatSend(contextId: String, content: String): ChatSendResponse {
        return ChatSendResponse(
            ok = true,
            userMessageId = "msg-u-${UUID.randomUUID().toString().take(12)}",
            assistantMessageId = "msg-a-${UUID.randomUUID().toString().take(12)}",
            createdAtMs = now()
        )
    }

    /**
     * Canned reply keyed by a substring match on the prompt. For the video
     * we need a handful of prompt → response mappings that look plausible.
     */
    fun fakeReplyFor(prompt: String): String {
        val lower = prompt.lowercase()
        return when {
            "hello" in lower || "hi" in lower ->
                "Hello — Agent Zero here. I'm routed through your SeekerZero mobile bridge. How can I help?"
            "weather" in lower ->
                "Checking the weather for your location… 🌤️ Currently 18°C, partly cloudy, light wind from the north-west. Afternoon high expected around 22°C."
            "solana" in lower ->
                "Solana is a high-performance layer-1 blockchain that uses a Proof-of-History timestamping mechanism alongside Proof-of-Stake. Current TPS averages around 3–5k in real conditions, with sub-second finality."
            "what can you do" in lower || "capabilities" in lower ->
                "I can run a full agent loop on your behalf: search, read files, delegate to subordinate models, execute code, manage scheduled tasks, and check approval gates. Anything that fits in the A0 skill graph."
            "status" in lower ->
                "All subsystems nominal. a0-think (Nemotron 120B) and a0-work (Qwen3.6 35B) both responding within normal latency envelopes. Daily spend tracking well under budget."
            else ->
                "Acknowledged. Processing \"$prompt\" against the current skill graph. Give me a moment to route through the appropriate subordinate…"
        }
    }

    // ---------- Chat streaming bus ----------------------------------------

    private val _chatBus = MutableSharedFlow<JsonObject>(
        replay = 0, extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val chatBus: SharedFlow<JsonObject> = _chatBus.asSharedFlow()

    suspend fun emitChatEvent(obj: JsonObject) {
        _chatBus.emit(obj)
    }

    fun userMsgEvent(messageId: String, content: String): JsonObject = buildJsonObject {
        put("type", "user_msg")
        put("message_id", messageId)
        put("role", "user")
        put("content", content)
        put("created_at_ms", now())
    }

    fun deltaEvent(messageId: String, delta: String): JsonObject = buildJsonObject {
        put("type", "delta")
        put("message_id", messageId)
        put("role", "assistant")
        put("delta", delta)
        put("created_at_ms", now())
    }

    fun finalEvent(messageId: String, content: String): JsonObject = buildJsonObject {
        put("type", "final")
        put("message_id", messageId)
        put("role", "assistant")
        put("content", content)
        put("created_at_ms", now())
    }

    fun toolCallEvent(messageId: String, tool: String): JsonObject = buildJsonObject {
        put("type", "tool_call")
        put("message_id", messageId)
        put("tool_name", tool)
        put("created_at_ms", now())
    }

    fun toolResultEvent(messageId: String, tool: String): JsonObject = buildJsonObject {
        put("type", "tool_result")
        put("message_id", messageId)
        put("tool_name", tool)
        put("result_preview", "(ok)")
        put("truncated", false)
        put("created_at_ms", now())
    }

    // ---------- Tasks ------------------------------------------------------

    private val tasks = mutableListOf(
        TaskDto(
            uuid = "demo-task-1",
            name = "daily-health-audit",
            state = "idle", type = "scheduled",
            schedule = TaskSchedule("0", "10", "*", "*", "1", "America/New_York"),
            lastRunMs = now() - 24 * 3600_000,
            nextRunMs = now() + 6 * 24 * 3600_000,
            lastResultPreview = "## Weekly Health Audit — 2026-04-20 ✅\n\nAll 6 phases completed. Telegram report delivered.",
            lastResultTruncated = true,
            createdAtMs = now() - 30L * 24 * 3600_000,
            updatedAtMs = now() - 24 * 3600_000
        ),
        TaskDto(
            uuid = "demo-task-2",
            name = "gmail-morning-digest",
            state = "idle", type = "scheduled",
            schedule = TaskSchedule("0", "7", "*", "*", "1,2,3,4,5", "America/New_York"),
            lastRunMs = now() - 9 * 3600_000,
            nextRunMs = now() + 15 * 3600_000,
            lastResultPreview = "Good morning! 4 emails since yesterday: 1 from bank (statement ready), 2 newsletters, 1 from Eric re: Friday. Full summary sent to Telegram.",
            lastResultTruncated = false,
            createdAtMs = now() - 45L * 24 * 3600_000,
            updatedAtMs = now() - 9 * 3600_000
        ),
        TaskDto(
            uuid = "demo-task-3",
            name = "solana-price-watch",
            state = "idle", type = "scheduled",
            schedule = TaskSchedule("*/15", "*", "*", "*", "*", "America/New_York"),
            lastRunMs = now() - 8 * 60_000,
            nextRunMs = now() + 7 * 60_000,
            lastResultPreview = "SOL: \$142.37 (+1.2% 24h). No alert thresholds crossed.",
            lastResultTruncated = false,
            createdAtMs = now() - 5L * 24 * 3600_000,
            updatedAtMs = now() - 8 * 60_000
        )
    )

    fun tasks(): TasksListResponse = TasksListResponse(
        tasks = tasks.toList(), serverTimeMs = now()
    )

    fun taskCreate(name: String, schedule: TaskSchedule): TaskCreateResponse {
        val uuid = "demo-task-${UUID.randomUUID().toString().take(6)}"
        val t = TaskDto(
            uuid = uuid, name = name, state = "idle", type = "scheduled",
            schedule = schedule,
            lastRunMs = 0, nextRunMs = now() + 3600_000,
            lastResultPreview = "", lastResultTruncated = false,
            createdAtMs = now(), updatedAtMs = now()
        )
        tasks.add(t)
        return TaskCreateResponse(ok = true, uuid = uuid, task = t)
    }

    fun taskRun(uuid: String): TaskActionResponse {
        val i = tasks.indexOfFirst { it.uuid == uuid }
        if (i >= 0) tasks[i] = tasks[i].copy(
            state = "idle",
            lastRunMs = now(),
            updatedAtMs = now(),
            lastResultPreview = "Run kicked off in demo mode; imagine an agent thought about something clever here."
        )
        return TaskActionResponse(ok = true, uuid = uuid, startedAtMs = now())
    }

    fun taskToggle(uuid: String, enabled: Boolean): TaskActionResponse {
        val i = tasks.indexOfFirst { it.uuid == uuid }
        if (i >= 0) tasks[i] = tasks[i].copy(
            state = if (enabled) "idle" else "disabled",
            updatedAtMs = now()
        )
        return TaskActionResponse(ok = true, uuid = uuid, state = tasks[i].state)
    }

    fun taskDelete(uuid: String) {
        tasks.removeAll { it.uuid == uuid }
    }

    // ---------- Status / health -------------------------------------------

    fun health(): HealthResponse = HealthResponse(
        ok = true,
        serverTimeMs = now(),
        a0Version = "0.9.8.2 (demo)",
        subordinates = listOf(
            SubordinateStatus(name = "a0-think", status = "up", lastResponseMs = 412),
            SubordinateStatus(name = "a0-work", status = "up", lastResponseMs = 174),
            SubordinateStatus(name = "a0-embed", status = "up", lastResponseMs = 89)
        ),
        erroredTasks = listOf(
            ErroredTask(
                uuid = "demo-err-1",
                name = "stale-market-scraper",
                state = "error",
                retryCount = 2,
                lastErrorAtMs = now() - 17 * 60_000,
                lastErrorPreview = "HTTP 503 from upstream; backing off until next scheduled run."
            )
        )
    )

    // Used by MobileApiClient.chatStream in demo mode — returns the bus.
    val json: Json = Json { ignoreUnknownKeys = true }
}
