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
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dev.seekerzero.app.MainActivity
import dev.seekerzero.app.SeekerZeroApplication
import dev.seekerzero.app.api.MobileApiClient
import dev.seekerzero.app.chat.ChatRepository
import dev.seekerzero.app.config.ConfigManager
import dev.seekerzero.app.util.ConnectionState
import dev.seekerzero.app.util.LogCollector
import dev.seekerzero.app.util.ServiceState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    private var pushPollerJob: Job? = null
    private val watchdog = ConnectionWatchdog()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    companion object {
        private const val TAG = "SeekerZeroService"
        private const val SERVICE_NOTIFICATION_ID = 1
        private const val CHAT_RECONNECT_DELAY_MS = 2000L
        private const val HEALTH_PING_INTERVAL_MS = 20_000L
        private const val CHAT_NOTIFICATION_ID_BASE = 1000
        private const val CHAT_NOTIFICATION_PREVIEW_CHARS = 140
        private const val PUSH_NOTIFICATION_ID_BASE = 2000
        private const val PUSH_RETRY_DELAY_MS = 5_000L
        const val EXTRA_CONTEXT_ID = "seekerzero.extra.context_id"
        const val EXTRA_INITIAL_TAB = "seekerzero.extra.initial_tab"

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
        startPushPoller()
        return START_STICKY
    }

    override fun onDestroy() {
        LogCollector.d(TAG, "onDestroy")
        healthJob?.cancel()
        chatControllerJob?.cancel()
        pushPollerJob?.cancel()
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

    /**
     * Post a system notification for an inbound assistant reply when the app
     * is not in the foreground. In-foreground replies are already rendered
     * inline in ChatScreen; posting a notification would be redundant.
     */
    private fun maybeNotifyOnFinal(contextId: String, event: JsonObject) {
        val type = event["type"]?.jsonPrimitive?.content
        if (type != "final") return
        if (isAppInForeground()) return
        val messageId = event["message_id"]?.jsonPrimitive?.content ?: return
        val content = event["content"]?.jsonPrimitive?.content.orEmpty().trim()
        if (content.isEmpty()) return
        val preview = if (content.length <= CHAT_NOTIFICATION_PREVIEW_CHARS) content
            else content.take(CHAT_NOTIFICATION_PREVIEW_CHARS).trimEnd() + "…"
        fireChatReplyNotification(contextId, messageId, preview)
    }

    private fun isAppInForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private fun fireChatReplyNotification(contextId: String, messageId: String, preview: String) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CONTEXT_ID, contextId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            // Request code derived from contextId so the most recent intent
            // for a given chat replaces earlier ones (FLAG_UPDATE_CURRENT).
            contextId.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = "Agent Zero"
        val notif = NotificationCompat.Builder(this, SeekerZeroApplication.CHANNEL_CHAT)
            .setContentTitle(title)
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // One slot per context so a second reply in the same chat replaces
        // the first (no "Agent Zero (2)" pile-ups in the shade).
        val notifId = CHAT_NOTIFICATION_ID_BASE + (contextId.hashCode() and 0x0fffffff)
        nm.notify(notifId, notif)
        LogCollector.d(TAG, "posted chat reply notification ctx=$contextId msg=$messageId")
    }

    /**
     * Long-poll the server-side push queue. Each returned batch is
     * notified on CHANNEL_SCHEDULED, then acked so the server stops
     * returning it. Always uses since_id=0 because the server filters to
     * undelivered rows — no client-side cursor needed. Retries forever on
     * failure (the watchdog pattern is overkill here since the server
     * already holds the poll open for ~55s on idle, making this loop
     * inherently low-volume).
     */
    private fun startPushPoller() {
        if (pushPollerJob?.isActive == true) return
        pushPollerJob = scope.launch {
            while (scope.isActive) {
                val result = MobileApiClient.pushPending(sinceId = 0L)
                result.onSuccess { resp ->
                    if (resp.items.isNotEmpty()) {
                        for (item in resp.items) {
                            firePushNotification(item)
                        }
                        val ids = resp.items.map { it.id }
                        MobileApiClient.pushAck(ids)
                            .onFailure {
                                // Ack failure is non-fatal: server keeps
                                // the rows as undelivered and the next
                                // poll will return them again. Idempotent
                                // notification slot (by id) means the user
                                // sees at most one replacement banner per
                                // row.
                                LogCollector.w(TAG, "pushAck failed: ${it.message}")
                            }
                    }
                }.onFailure {
                    // Expected every time the poll times out on an idle
                    // server (returns 200 with empty items, not an error)
                    // OR on transient network blips. Short retry delay.
                    LogCollector.w(TAG, "pushPending failed: ${it.message}")
                    delay(PUSH_RETRY_DELAY_MS)
                }
            }
        }
    }

    private fun firePushNotification(item: dev.seekerzero.app.api.models.PushItem) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            applyDeepLink(this, item.deepLink)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            item.id.toInt(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val preview = if (item.body.length <= CHAT_NOTIFICATION_PREVIEW_CHARS) item.body
            else item.body.take(CHAT_NOTIFICATION_PREVIEW_CHARS).trimEnd() + "…"
        val notif = NotificationCompat.Builder(this, SeekerZeroApplication.CHANNEL_SCHEDULED)
            .setContentTitle(item.title.ifBlank { "Agent Zero" })
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // One slot per push id — unique per queue row, so parallel
        // deliveries don't collapse into each other.
        val notifId = PUSH_NOTIFICATION_ID_BASE + (item.id.toInt() and 0x0fffffff)
        nm.notify(notifId, notif)
        LogCollector.d(TAG, "posted scheduled push id=${item.id} title=${item.title}")
    }

    /**
     * Turn a `sz://tasks/<uuid>` or `sz://chat/<ctxId>` deep link into
     * activity intent extras. Scheduled-task notifications always deep-
     * link to the chat tab (without overriding the active context) so the
     * user lands where they expect to continue the conversation; they can
     * navigate to Tasks from there if they want the full task history.
     * Unknown or null links → no extras (app just opens on its default tab).
     */
    private fun applyDeepLink(intent: Intent, deepLink: String?) {
        if (deepLink.isNullOrBlank()) return
        val trimmed = deepLink.removePrefix("sz://")
        when {
            trimmed.startsWith("tasks/") -> {
                intent.putExtra(EXTRA_INITIAL_TAB, "chat")
            }
            trimmed.startsWith("chat/") -> {
                val ctxId = trimmed.removePrefix("chat/").substringBefore('/')
                if (ctxId.isNotBlank()) {
                    intent.putExtra(EXTRA_INITIAL_TAB, "chat")
                    intent.putExtra(EXTRA_CONTEXT_ID, ctxId)
                }
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
                                    maybeNotifyOnFinal(contextId, event)
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
