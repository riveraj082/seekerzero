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

    private const val DEFAULT_API_BASE = "/mobile"
    private const val DEFAULT_PORT = 50080

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

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _a0Host.value = prefs.getString(K_A0_HOST, null)
        _port.value = prefs.getInt(K_PORT, DEFAULT_PORT)
        _mobileApiBase.value = prefs.getString(K_MOBILE_API_BASE, DEFAULT_API_BASE) ?: DEFAULT_API_BASE
        _clientId.value = prefs.getString(K_CLIENT_ID, null)
        _displayName.value = prefs.getString(K_DISPLAY_NAME, null)
        _lastContactAtMs.value = prefs.getLong(K_LAST_CONTACT, 0L)
        _serviceEnabled.value = prefs.getBoolean(K_SERVICE_ENABLED, false)
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
