package dev.seekerzero.app.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ConnectionState { DISCONNECTED, PAUSED_NO_NETWORK, RECONNECTING, CONNECTED, OFFLINE }

object ServiceState {
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _lastContactAtMs = MutableStateFlow(0L)
    val lastContactAtMs: StateFlow<Long> = _lastContactAtMs

    private val _reconnectCount = MutableStateFlow(0)
    val reconnectCount: StateFlow<Int> = _reconnectCount

    private val _chatAttached = MutableStateFlow(false)
    val chatAttached: StateFlow<Boolean> = _chatAttached

    fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    fun markContact(atMs: Long) {
        _lastContactAtMs.value = atMs
    }

    fun incrementReconnectCount() {
        _reconnectCount.value = _reconnectCount.value + 1
    }

    fun setChatAttached(attached: Boolean) {
        _chatAttached.value = attached
    }
}
