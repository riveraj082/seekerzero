package dev.seekerzero.app.ui.chat

import dev.seekerzero.app.util.LogCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

object FakeChatSource {

    private const val TAG = "FakeChatSource"

    private val _messages = MutableStateFlow<List<ChatMessage>>(seed())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    fun send(scope: CoroutineScope, text: String) {
        if (text.isBlank() || _streaming.value) return

        val now = System.currentTimeMillis()
        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatRole.USER,
            content = text.trim(),
            createdAtMs = now,
            isFinal = true
        )
        _messages.value = _messages.value + userMsg

        val assistantId = UUID.randomUUID().toString()
        val assistantStart = ChatMessage(
            id = assistantId,
            role = ChatRole.ASSISTANT,
            content = "",
            createdAtMs = now + 1,
            isFinal = false
        )
        _messages.value = _messages.value + assistantStart

        scope.launch(Dispatchers.Default) {
            _streaming.value = true
            try {
                val reply = fakeReplyFor(text.trim())
                val buf = StringBuilder()
                for (token in reply.split(' ')) {
                    delay(60)
                    if (buf.isNotEmpty()) buf.append(' ')
                    buf.append(token)
                    updateAssistant(assistantId, buf.toString(), isFinal = false)
                }
                updateAssistant(assistantId, buf.toString(), isFinal = true)
                LogCollector.d(TAG, "fake stream completed for $assistantId")
            } finally {
                _streaming.value = false
            }
        }
    }

    private fun updateAssistant(id: String, content: String, isFinal: Boolean) {
        _messages.value = _messages.value.map {
            if (it.id == id) it.copy(content = content, isFinal = isFinal) else it
        }
    }

    private fun fakeReplyFor(prompt: String): String {
        val lower = prompt.lowercase()
        return when {
            "hello" in lower || "hi" in lower ->
                "Hello. Agent Zero is online. This is fake chat data wired into the UI shell."
            "status" in lower ->
                "Fake status: all subsystems nominal. Real backend wiring lands in the next step of Phase 5."
            "?" in prompt ->
                "Good question. Once /mobile/chat/stream is wired, you'll get real token-streamed answers here."
            else ->
                "Echo: \"$prompt\". Streaming one token at a time to exercise the UI."
        }
    }

    private fun seed(): List<ChatMessage> {
        val base = System.currentTimeMillis() - 60_000
        return listOf(
            ChatMessage(
                id = "seed-1",
                role = ChatRole.ASSISTANT,
                content = "SeekerZero chat shell (fake data). Type anything to see token streaming.",
                createdAtMs = base,
                isFinal = true
            )
        )
    }
}
