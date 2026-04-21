package dev.seekerzero.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow

class ChatViewModel : ViewModel() {

    val messages: StateFlow<List<ChatMessage>> = FakeChatSource.messages
    val streaming: StateFlow<Boolean> = FakeChatSource.streaming

    fun send(text: String) {
        FakeChatSource.send(viewModelScope, text)
    }
}
