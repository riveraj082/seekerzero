package dev.seekerzero.app.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ConnectionState { DISCONNECTED, PAUSED_NO_NETWORK, RECONNECTING, CONNECTED, OFFLINE }

object ServiceState {
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _pendingApprovals = MutableStateFlow<List<Any>>(emptyList())
    val pendingApprovals: StateFlow<List<Any>> = _pendingApprovals

    private val _lastContactAtMs = MutableStateFlow(0L)
    val lastContactAtMs: StateFlow<Long> = _lastContactAtMs

    private val _reconnectCount = MutableStateFlow(0)
    val reconnectCount: StateFlow<Int> = _reconnectCount
}
