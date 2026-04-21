package dev.seekerzero.app.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.seekerzero.app.api.models.ChatContext
import dev.seekerzero.app.chat.ChatMessageEntity
import dev.seekerzero.app.chat.ChatRepository
import dev.seekerzero.app.chat.ToolActivity
import dev.seekerzero.app.config.ConfigManager
import dev.seekerzero.app.util.ServiceState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: ChatRepository = ChatRepository.get(app)

    val activeContextId: StateFlow<String> = ConfigManager.activeChatContextFlow
    val contexts: StateFlow<List<ChatContext>> = repo.remoteContexts

    val messages: StateFlow<List<ChatMessage>> = activeContextId
        .flatMapLatest { ctx -> repo.messages(ctx) }
        .map { list -> list.map { it.toUi() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val streaming: StateFlow<Boolean> = repo.streaming
    val activeTool: StateFlow<String?> = repo.activeTool
    val currentTurnTools: StateFlow<List<ToolActivity>> = repo.currentTurnTools

    init {
        viewModelScope.launch { repo.refreshContexts() }
    }

    fun attach() = ServiceState.setChatAttached(true)
    fun detach() = ServiceState.setChatAttached(false)

    fun send(text: String) {
        viewModelScope.launch {
            repo.send(activeContextId.value, text)
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
