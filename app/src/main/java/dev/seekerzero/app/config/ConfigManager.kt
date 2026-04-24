package dev.seekerzero.app.config

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object ConfigManager {
    private const val PREFS = "seekerzero_prefs"

    private const val K_A0_HOST = "a0_host"
    private const val K_PORT = "port"
    private const val K_MOBILE_API_BASE = "mobile_api_base"
    private const val K_CLIENT_ID = "client_id"
    private const val K_DISPLAY_NAME = "display_name"
    private const val K_LAST_CONTACT = "last_contact_at_ms"
    private const val K_SERVICE_ENABLED = "service_enabled"
    private const val K_ACTIVE_CHAT_CONTEXT = "active_chat_context"
    private const val K_DEMO_MODE = "demo_mode"
    private const val K_USER_AVATAR_PATH = "user_avatar_path"

    private const val DEFAULT_API_BASE = "/mobile"
    private const val DEFAULT_PORT = 50080
    private const val DEFAULT_CHAT_CONTEXT = "mobile-seekerzero"

    private lateinit var prefs: SharedPreferences

    private val _a0Host = MutableStateFlow<String?>(null)
    val a0HostFlow: StateFlow<String?> = _a0Host

    private val _port = MutableStateFlow(DEFAULT_PORT)
    val portFlow: StateFlow<Int> = _port

    private val _mobileApiBase = MutableStateFlow(DEFAULT_API_BASE)
    val mobileApiBaseFlow: StateFlow<String> = _mobileApiBase

    private val _clientId = MutableStateFlow<String?>(null)
    val clientIdFlow: StateFlow<String?> = _clientId

    private val _displayName = MutableStateFlow<String?>(null)
    val displayNameFlow: StateFlow<String?> = _displayName

    private val _lastContactAtMs = MutableStateFlow(0L)
    val lastContactAtMsFlow: StateFlow<Long> = _lastContactAtMs

    private val _serviceEnabled = MutableStateFlow(false)
    val serviceEnabledFlow: StateFlow<Boolean> = _serviceEnabled

    private val _activeChatContext = MutableStateFlow(DEFAULT_CHAT_CONTEXT)
    val activeChatContextFlow: StateFlow<String> = _activeChatContext

    // Intent-delivered request to open a specific top-level tab on the next
    // composition. MainScaffold consumes this once and clears it. Not
    // persisted — only meaningful for the in-flight notification tap.
    private val _pendingInitialTab = MutableStateFlow<String?>(null)
    val pendingInitialTabFlow: StateFlow<String?> = _pendingInitialTab
    var pendingInitialTab: String?
        get() = _pendingInitialTab.value
        set(value) { _pendingInitialTab.value = value }

    private val _demoMode = MutableStateFlow(false)
    val demoModeFlow: StateFlow<Boolean> = _demoMode

    private val _userAvatarPath = MutableStateFlow<String?>(null)
    val userAvatarPathFlow: StateFlow<String?> = _userAvatarPath

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _a0Host.value = prefs.getString(K_A0_HOST, null)
        _port.value = prefs.getInt(K_PORT, DEFAULT_PORT)
        _mobileApiBase.value = prefs.getString(K_MOBILE_API_BASE, DEFAULT_API_BASE) ?: DEFAULT_API_BASE
        _clientId.value = prefs.getString(K_CLIENT_ID, null)
        _displayName.value = prefs.getString(K_DISPLAY_NAME, null)
        _lastContactAtMs.value = prefs.getLong(K_LAST_CONTACT, 0L)
        _serviceEnabled.value = prefs.getBoolean(K_SERVICE_ENABLED, false)
        _activeChatContext.value = prefs.getString(K_ACTIVE_CHAT_CONTEXT, DEFAULT_CHAT_CONTEXT)
            ?: DEFAULT_CHAT_CONTEXT
        _demoMode.value = prefs.getBoolean(K_DEMO_MODE, false)
        _userAvatarPath.value = prefs.getString(K_USER_AVATAR_PATH, null)
    }

    var a0Host: String?
        get() = _a0Host.value
        set(value) {
            prefs.edit().apply { if (value == null) remove(K_A0_HOST) else putString(K_A0_HOST, value) }.apply()
            _a0Host.value = value
        }

    var port: Int
        get() = _port.value
        set(value) {
            prefs.edit().putInt(K_PORT, value).apply()
            _port.value = value
        }

    var mobileApiBase: String
        get() = _mobileApiBase.value
        set(value) {
            prefs.edit().putString(K_MOBILE_API_BASE, value).apply()
            _mobileApiBase.value = value
        }

    var clientId: String?
        get() = _clientId.value
        set(value) {
            prefs.edit().apply { if (value == null) remove(K_CLIENT_ID) else putString(K_CLIENT_ID, value) }.apply()
            _clientId.value = value
        }

    var displayName: String?
        get() = _displayName.value
        set(value) {
            prefs.edit().apply { if (value == null) remove(K_DISPLAY_NAME) else putString(K_DISPLAY_NAME, value) }.apply()
            _displayName.value = value
        }

    var lastContactAtMs: Long
        get() = _lastContactAtMs.value
        set(value) {
            prefs.edit().putLong(K_LAST_CONTACT, value).apply()
            _lastContactAtMs.value = value
        }

    var serviceEnabled: Boolean
        get() = _serviceEnabled.value
        set(value) {
            prefs.edit().putBoolean(K_SERVICE_ENABLED, value).apply()
            _serviceEnabled.value = value
        }

    var activeChatContext: String
        get() = _activeChatContext.value
        set(value) {
            val v = if (value.isBlank()) DEFAULT_CHAT_CONTEXT else value
            prefs.edit().putString(K_ACTIVE_CHAT_CONTEXT, v).apply()
            _activeChatContext.value = v
        }

    var demoMode: Boolean
        get() = _demoMode.value
        set(value) {
            prefs.edit().putBoolean(K_DEMO_MODE, value).apply()
            _demoMode.value = value
        }

    var userAvatarPath: String?
        get() = _userAvatarPath.value
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(K_USER_AVATAR_PATH) else putString(K_USER_AVATAR_PATH, value)
            }.apply()
            _userAvatarPath.value = value
        }

    /**
     * Pre-fill config with placeholder values so `isConfigured()` returns
     * true without a QR scan. Used by the "Enter demo mode" path on the
     * Setup screen to unlock MainScaffold without real a0prod info.
     */
    fun applyDemoDefaults() {
        prefs.edit()
            .putString(K_A0_HOST, "demo.local")
            .putInt(K_PORT, DEFAULT_PORT)
            .putString(K_MOBILE_API_BASE, DEFAULT_API_BASE)
            .putString(K_CLIENT_ID, "demo-client")
            .putString(K_DISPLAY_NAME, "Demo")
            .putBoolean(K_DEMO_MODE, true)
            .apply()
        _a0Host.value = "demo.local"
        _port.value = DEFAULT_PORT
        _mobileApiBase.value = DEFAULT_API_BASE
        _clientId.value = "demo-client"
        _displayName.value = "Demo"
        _demoMode.value = true
    }

    fun isConfigured(): Boolean = a0Host != null && clientId != null

    fun applyPayload(payload: QrConfigPayload) {
        prefs.edit()
            .putString(K_A0_HOST, payload.a0Host)
            .putInt(K_PORT, payload.port)
            .putString(K_MOBILE_API_BASE, payload.mobileApiBase)
            .putString(K_CLIENT_ID, payload.clientId)
            .putString(K_DISPLAY_NAME, payload.displayName)
            .apply()
        _a0Host.value = payload.a0Host
        _port.value = payload.port
        _mobileApiBase.value = payload.mobileApiBase
        _clientId.value = payload.clientId
        _displayName.value = payload.displayName
    }
}
