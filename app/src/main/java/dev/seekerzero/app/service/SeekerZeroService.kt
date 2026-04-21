package dev.seekerzero.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.seekerzero.app.MainActivity
import dev.seekerzero.app.SeekerZeroApplication
import dev.seekerzero.app.api.MobileApiClient
import dev.seekerzero.app.chat.ChatRepository
import dev.seekerzero.app.config.ConfigManager
import dev.seekerzero.app.util.ConnectionState
import dev.seekerzero.app.util.LogCollector
import dev.seekerzero.app.util.ServiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SeekerZeroService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var healthJob: Job? = null
    private var chatControllerJob: Job? = null
    private val watchdog = ConnectionWatchdog()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    companion object {
        private const val TAG = "SeekerZeroService"
        private const val SERVICE_NOTIFICATION_ID = 1
        private const val CHAT_RECONNECT_DELAY_MS = 2000L
        private const val HEALTH_PING_INTERVAL_MS = 20_000L

        fun start(context: Context) {
            val intent = Intent(context, SeekerZeroService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SeekerZeroService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        LogCollector.d(TAG, "onCreate")
        registerNetworkCallback()
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                LogCollector.d(TAG, "network onAvailable")
                watchdog.signalNetworkAvailable()
                if (ServiceState.connectionState.value == ConnectionState.PAUSED_NO_NETWORK) {
                    ServiceState.setConnectionState(ConnectionState.RECONNECTING)
                }
            }

            override fun onLost(network: Network) {
                LogCollector.d(TAG, "network onLost")
                ServiceState.setConnectionState(ConnectionState.PAUSED_NO_NETWORK)
            }
        }
        cm.registerNetworkCallback(request, cb)
        networkCallback = cb
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(cb)
        } catch (t: Throwable) {
            LogCollector.w(TAG, "unregisterNetworkCallback failed: ${t.message}")
        }
        networkCallback = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogCollector.d(TAG, "onStartCommand")
        startForegroundWithNotification()
        ConfigManager.serviceEnabled = true
        startHealthPing()
        startChatController()
        return START_STICKY
    }

    override fun onDestroy() {
        LogCollector.d(TAG, "onDestroy")
        healthJob?.cancel()
        chatControllerJob?.cancel()
        scope.cancel()
        unregisterNetworkCallback()
        ServiceState.setConnectionState(ConnectionState.DISCONNECTED)
        super.onDestroy()
    }

    private fun startForegroundWithNotification() {
        val notification = buildServiceNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                SERVICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(SERVICE_NOTIFICATION_ID, notification)
        }
    }

    private fun buildServiceNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, SeekerZeroApplication.CHANNEL_SERVICE)
            .setContentTitle("SeekerZero")
            .setContentText("Connected to Agent Zero")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(false)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pendingIntent)
            .build()
    }

    /**
     * Liveness ping against `/mobile/health` every 20 seconds. Replaces the
     * approvals long-poll that used to drive the connection indicator; pure
     * health signal now, no stub machinery. Cheap on battery and server.
     */
    private fun startHealthPing() {
        if (healthJob?.isActive == true) return
        healthJob = scope.launch {
            while (scope.isActive) {
                MobileApiClient.health()
                    .onSuccess { resp ->
                        ServiceState.markContact(resp.serverTimeMs)
                        ServiceState.setConnectionState(ConnectionState.CONNECTED)
                        watchdog.resetBackoff()
                        LogCollector.d(TAG, "health ping ok (a0=${resp.a0Version})")
                    }
                    .onFailure { err ->
                        if (ServiceState.connectionState.value != ConnectionState.PAUSED_NO_NETWORK) {
                            ServiceState.setConnectionState(ConnectionState.RECONNECTING)
                        }
                        ServiceState.incrementReconnectCount()
                        LogCollector.w(TAG, "health ping failed: ${err.message}")
                    }
                delay(HEALTH_PING_INTERVAL_MS)
            }
        }
    }

    private fun startChatController() {
        if (chatControllerJob?.isActive == true) return
        val repo = ChatRepository.get(applicationContext)
        chatControllerJob = scope.launch {
            // Three inputs: whether the screen is attached, whether a reply
            // is in flight, and which context is active. When any of these
            // changes, collectLatest cancels the previous inner block and
            // restarts — context-switch = close old stream, open new one.
            combine(
                ServiceState.chatAttached,
                repo.streaming,
                ConfigManager.activeChatContextFlow
            ) { attached, streaming, ctxId ->
                Triple(attached || streaming, ctxId, attached)
            }
                .distinctUntilChanged()
                .collectLatest { (shouldStream, contextId, _) ->
                    if (!shouldStream) {
                        LogCollector.d(TAG, "chat stream: idle")
                        return@collectLatest
                    }
                    LogCollector.d(TAG, "chat stream: opening for $contextId")
                    try {
                        while (scope.isActive) {
                            try {
                                repo.refreshHistory(contextId)
                                val sinceMs = repo.maxFinalMs(contextId)
                                MobileApiClient.chatStream(
                                    contextId = contextId,
                                    sinceMs = sinceMs
                                ) { event ->
                                    repo.applyEvent(contextId, event)
                                }
                                LogCollector.d(TAG, "chat stream ended cleanly; reconnecting")
                            } catch (t: Throwable) {
                                if (!scope.isActive) throw t
                                LogCollector.w(TAG, "chat stream error: ${t.message}; retrying")
                                delay(CHAT_RECONNECT_DELAY_MS)
                            }
                        }
                    } finally {
                        LogCollector.d(TAG, "chat stream: closing ($contextId)")
                        repo.markStreamingIdle()
                    }
                }
        }
    }
}
