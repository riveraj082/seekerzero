package dev.seekerzero.app.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.seekerzero.app.api.MobileApiClient
import dev.seekerzero.app.api.models.HealthResponse
import dev.seekerzero.app.config.ConfigManager
import dev.seekerzero.app.config.QrConfigPayload
import dev.seekerzero.app.config.QrParser
import dev.seekerzero.app.util.LogCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SetupUiState {
    data object Welcome : SetupUiState
    data object ManualEntry : SetupUiState
    data object Verifying : SetupUiState
    data class HealthFailed(val message: String) : SetupUiState
    data class NotificationPermission(val health: HealthResponse) : SetupUiState
    data class BatteryOptimization(val health: HealthResponse) : SetupUiState
    data class Done(val health: HealthResponse) : SetupUiState
}

class SetupViewModel : ViewModel() {

    private val tag = "SetupViewModel"

    private val _state = MutableStateFlow<SetupUiState>(SetupUiState.Welcome)
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    fun onStartScan() {
        // Caller launches QrScannerActivity. VM doesn't drive the launch.
    }

    fun onScanCancelled() {
        _state.value = SetupUiState.Welcome
    }

    fun onScanResult(raw: String) {
        _state.value = SetupUiState.Verifying
        viewModelScope.launch {
            val parsed = QrParser.parse(raw)
            if (parsed.isFailure) {
                val err = parsed.exceptionOrNull()
                LogCollector.w(tag, "QR parse failed: ${err?.message}")
                _state.value = SetupUiState.HealthFailed(err?.message ?: "Unreadable QR payload.")
                return@launch
            }
            applyAndVerify(parsed.getOrThrow())
        }
    }

    fun onManualEntrySelected() {
        _state.value = SetupUiState.ManualEntry
    }

    fun onManualEntry(host: String, clientId: String?) {
        val trimmedHost = host.trim()
        if (trimmedHost.isEmpty()) {
            _state.value = SetupUiState.HealthFailed("Tailnet host cannot be empty.")
            return
        }
        val effectiveClientId = clientId?.trim()?.takeIf { it.isNotEmpty() } ?: "seekerzero-manual"
        val payload = QrConfigPayload(
            v = 1,
            a0Host = trimmedHost,
            mobileApiBase = "/mobile",
            clientId = effectiveClientId,
            displayName = "SeekerZero (manual)"
        )
        _state.value = SetupUiState.Verifying
        viewModelScope.launch { applyAndVerify(payload) }
    }

    fun onRetryHealth() {
        val host = ConfigManager.a0Host
        if (host == null) {
            _state.value = SetupUiState.Welcome
            return
        }
        _state.value = SetupUiState.Verifying
        viewModelScope.launch { verify() }
    }

    fun onStartOver() {
        ConfigManager.a0Host = null
        ConfigManager.clientId = null
        ConfigManager.displayName = null
        _state.value = SetupUiState.Welcome
    }

    fun onNotificationPermissionResolved() {
        val current = _state.value
        if (current is SetupUiState.NotificationPermission) {
            _state.value = SetupUiState.BatteryOptimization(current.health)
        }
    }

    fun onBatteryOptimizationResolved() {
        val current = _state.value
        if (current is SetupUiState.BatteryOptimization) {
            _state.value = SetupUiState.Done(current.health)
        }
    }

    fun onDismissDone() {
        // Navigation handled by the screen; VM state is terminal.
    }

    private suspend fun applyAndVerify(payload: QrConfigPayload) {
        ConfigManager.applyPayload(payload)
        verify()
    }

    private suspend fun verify() {
        val result = MobileApiClient.health()
        result.fold(
            onSuccess = { health ->
                if (!health.ok) {
                    _state.value = SetupUiState.HealthFailed("Server returned ok=false.")
                    return
                }
                ConfigManager.lastContactAtMs = System.currentTimeMillis()
                LogCollector.i(tag, "Health OK: a0_version=${health.a0Version}, subordinates=${health.subordinates.size}")
                _state.value = SetupUiState.NotificationPermission(health)
            },
            onFailure = { err ->
                LogCollector.w(tag, "Health check failed: ${err.message}")
                _state.value = SetupUiState.HealthFailed(err.message ?: "Unknown error.")
            }
        )
    }
}
