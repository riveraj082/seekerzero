package dev.seekerzero.app.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.seekerzero.app.chat.ChatMessageEntity
import dev.seekerzero.app.chat.ChatRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: ChatRepository = ChatRepository.get(app)
    private val contextId: String = ChatRepository.DEFAULT_CONTEXT

    val messages: StateFlow<List<ChatMessage>> = repo.messages(contextId)
        .map { list -> list.map { it.toUi() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val streaming: StateFlow<Boolean> = repo.streaming
    val connected: StateFlow<Boolean> = repo.connected

    fun attach() = repo.attach(contextId)
    fun detach() = repo.detach()

    fun send(text: String) {
        viewModelScope.launch {
            repo.send(contextId, text)
        }
    }

    private fun ChatMessageEntity.toUi() = ChatMessage(
        id = id,
        role = if (role == "user") ChatRole.USER else ChatRole.ASSISTANT,
        content = content,
        createdAtMs = created_at_ms,
        isFinal = is_final
    )
}
