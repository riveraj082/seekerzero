package dev.seekerzero.app.ui.terminal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.seekerzero.app.config.ConfigManager
import dev.seekerzero.app.ssh.SshClient
import dev.seekerzero.app.ssh.SshKeyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface TerminalState {
    data object Idle : TerminalState
    data object Connecting : TerminalState
    data object Connected : TerminalState
    data class AuthFailed(val message: String) : TerminalState
    data class NetworkError(val message: String) : TerminalState
}

data class TerminalEntry(
    val id: Long,
    val command: String,
    val output: String,
    val startedAtMs: Long,
    val endedAtMs: Long?
) {
    val inFlight: Boolean get() = endedAtMs == null
}

class TerminalViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<TerminalState>(TerminalState.Idle)
    val state: StateFlow<TerminalState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<TerminalEntry>>(emptyList())
    val log: StateFlow<List<TerminalEntry>> = _log.asStateFlow()

    private val _publicKey = MutableStateFlow<String?>(null)
    val publicKey: StateFlow<String?> = _publicKey.asStateFlow()

    private var entryCounter = 0L

    init {
        viewModelScope.launch {
            _publicKey.value = runCatching { SshKeyManager.getPublicKeyOpenSsh() }.getOrNull()
            // Run an Ed25519 sign self-test on an IO thread before we let
            // sshj touch anything — this isolates whether Keystore-level
            // Ed25519 signing actually works on this device. Result goes
            // to logcat under tag SshKeyManager.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                SshKeyManager.selfTestSign()
            }
            tryConnect()
        }
    }

    /**
     * Eager connect: attempt an SSH connection on tab entry. If auth
     * succeeds, flip to Connected. If it fails, stay in an error state
     * that the UI presents alongside the setup key-install instructions
     * — the user probably needs to paste the public key into a0prod's
     * authorized_keys before we can get any further.
     */
    fun tryConnect() {
        if (_state.value is TerminalState.Connecting || _state.value is TerminalState.Connected) return
        val host = ConfigManager.a0Host
        if (host.isNullOrBlank()) {
            _state.value = TerminalState.NetworkError("a0 host not configured; complete first-run setup")
            return
        }
        val hostNoPort = host.removePrefix("http://").removePrefix("https://").substringBefore(':')
        viewModelScope.launch {
            _state.value = TerminalState.Connecting
            SshClient.connect(host = hostNoPort, port = 22, user = "a0user")
                .onSuccess { _state.value = TerminalState.Connected }
                .onFailure { err ->
                    val msg = err.message ?: err.javaClass.simpleName
                    _state.value = if (msg.contains("auth", ignoreCase = true) ||
                        msg.contains("publickey", ignoreCase = true)
                    ) {
                        TerminalState.AuthFailed(msg)
                    } else {
                        TerminalState.NetworkError(msg)
                    }
                }
        }
    }

    fun run(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        if (_state.value !is TerminalState.Connected) return
        val id = ++entryCounter
        val startedAt = System.currentTimeMillis()
        _log.value = _log.value + TerminalEntry(id, trimmed, "", startedAt, null)
        viewModelScope.launch {
            val result = SshClient.exec(trimmed)
            val endedAt = System.currentTimeMillis()
            val output = result.getOrElse { "[error] ${it.message ?: it.javaClass.simpleName}" }
            _log.value = _log.value.map {
                if (it.id == id) it.copy(output = output, endedAtMs = endedAt) else it
            }
        }
    }

    fun disconnect() {
        SshClient.disconnect()
        _state.value = TerminalState.Idle
    }

    override fun onCleared() {
        // Keep the SSH session alive across recompositions; don't disconnect
        // on VM clear. The service doesn't own this yet; Phase 6d will wire
        // a persistent session boundary.
    }
}
