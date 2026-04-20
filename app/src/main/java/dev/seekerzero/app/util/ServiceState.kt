package dev.seekerzero.app.util

import dev.seekerzero.app.api.models.Approval
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ConnectionState { DISCONNECTED, PAUSED_NO_NETWORK, RECONNECTING, CONNECTED, OFFLINE }

object ServiceState {
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _pendingApprovals = MutableStateFlow<List<Approval>>(emptyList())
    val pendingApprovals: StateFlow<List<Approval>> = _pendingApprovals

    private val _lastContactAtMs = MutableStateFlow(0L)
    val lastContactAtMs: StateFlow<Long> = _lastContactAtMs

    private val _reconnectCount = MutableStateFlow(0)
    val reconnectCount: StateFlow<Int> = _reconnectCount

    fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    fun markContact(atMs: Long) {
        _lastContactAtMs.value = atMs
    }

    fun mergePendingApprovals(incoming: List<Approval>) {
        if (incoming.isEmpty()) return
        val current = _pendingApprovals.value
        val byId = LinkedHashMap<String, Approval>(current.size + incoming.size)
        current.forEach { byId[it.id] = it }
        incoming.forEach { byId[it.id] = it }
        _pendingApprovals.value = byId.values.toList()
    }

    fun replacePendingApprovals(list: List<Approval>) {
        _pendingApprovals.value = list
    }

    fun removeApproval(id: String) {
        _pendingApprovals.value = _pendingApprovals.value.filterNot { it.id == id }
    }

    fun incrementReconnectCount() {
        _reconnectCount.value = _reconnectCount.value + 1
    }
}
