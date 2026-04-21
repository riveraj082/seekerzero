package dev.seekerzero.app.ui.status

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.seekerzero.app.api.MobileApiClient
import dev.seekerzero.app.api.models.HealthResponse
import dev.seekerzero.app.util.ConnectionState
import dev.seekerzero.app.util.ServiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class StatusViewModel(app: Application) : AndroidViewModel(app) {

    val connectionState: StateFlow<ConnectionState> = ServiceState.connectionState
    val lastContactAtMs: StateFlow<Long> = ServiceState.lastContactAtMs
    val reconnectCount: StateFlow<Int> = ServiceState.reconnectCount

    private val _health = MutableStateFlow<HealthResponse?>(null)
    val health: StateFlow<HealthResponse?> = _health.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            MobileApiClient.health().onSuccess { _health.value = it }
        }
    }
}
