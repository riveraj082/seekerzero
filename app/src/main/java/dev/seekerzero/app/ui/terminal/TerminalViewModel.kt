package dev.seekerzero.app.ui.terminal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.seekerzero.app.config.ConfigManager
import dev.seekerzero.app.ssh.SshClient
import dev.seekerzero.app.ssh.SshKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface TerminalState {
    data object Idle : TerminalState
    data object Connecting : TerminalState
    data object Connected : TerminalState
    data class AuthFailed(val message: String) : TerminalState
    data class NetworkError(val message: String) : TerminalState
}

class TerminalViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<TerminalState>(TerminalState.Idle)
    val state: StateFlow<TerminalState> = _state.asStateFlow()

    private val _scrollback = MutableStateFlow("")
    val scrollback: StateFlow<String> = _scrollback.asStateFlow()

    private val _publicKey = MutableStateFlow<String?>(null)
    val publicKey: StateFlow<String?> = _publicKey.asStateFlow()

    init {
        viewModelScope.launch {
            _publicKey.value = runCatching { SshKeyManager.getPublicKeyOpenSsh() }.getOrNull()
            withContext(Dispatchers.IO) { SshKeyManager.selfTestSign() }
            tryConnect()
        }
        // Collect shell output across the VM lifetime, append (stripped)
        // to scrollback. SharedFlow has no replay so late collectors won't
        // miss anything written after collection starts.
        viewModelScope.launch {
            SshClient.shellOutput.collect { bytes ->
                val text = String(bytes, Charsets.UTF_8)
                val clean = stripAnsi(text)
                val current = _scrollback.value
                val next = (current + clean).let {
                    if (it.length > MAX_SCROLLBACK) it.takeLast(MAX_SCROLLBACK) else it
                }
                _scrollback.value = next
            }
        }
    }

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
                .onSuccess {
                    SshClient.openShell()
                        .onSuccess { _state.value = TerminalState.Connected }
                        .onFailure { err ->
                            _state.value = TerminalState.NetworkError(
                                "shell: ${err.message ?: err.javaClass.simpleName}"
                            )
                        }
                }
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

    /** Send text + newline to the shell's stdin. */
    fun sendLine(text: String) {
        if (_state.value !is TerminalState.Connected) return
        viewModelScope.launch {
            SshClient.sendInput((text + "\n").toByteArray(Charsets.UTF_8))
        }
    }

    /** Raw input (e.g., Ctrl-C = 0x03, Tab = 0x09). Used when we wire a real keyboard. */
    fun sendRaw(bytes: ByteArray) {
        if (_state.value !is TerminalState.Connected) return
        viewModelScope.launch {
            SshClient.sendInput(bytes)
        }
    }

    fun clearScrollback() {
        _scrollback.value = ""
    }

    fun disconnect() {
        SshClient.disconnect()
        _state.value = TerminalState.Idle
    }

    companion object {
        private const val MAX_SCROLLBACK = 128 * 1024 // 128 KB keeps memory in check

        /**
         * Strip common ANSI escape sequences (CSI, OSC, single-char ESC
         * sequences) so the scrollback renders as readable text. This is a
         * dumb terminal — no cursor control, no colors, no alternate screen.
         * Programs that rely on those (vim, htop, tmux) will look garbled;
         * bash, ls, cat, ps, cd, env, grep, tail, head, etc. all work fine.
         */
        private val ANSI_CSI = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
        private val ANSI_OSC = Regex("\u001B\\].*?(?:\u0007|\u001B\\\\)")
        private val ANSI_OTHER = Regex("\u001B[@-Z\\\\-_]")

        fun stripAnsi(input: String): String {
            return input
                .replace(ANSI_CSI, "")
                .replace(ANSI_OSC, "")
                .replace(ANSI_OTHER, "")
        }
    }
}
