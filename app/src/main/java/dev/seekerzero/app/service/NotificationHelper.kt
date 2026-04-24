package dev.seekerzero.app.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.seekerzero.app.MainActivity
import dev.seekerzero.app.SeekerZeroApplication

/**
 * Standalone helpers for posting notifications from anywhere (UI, tests).
 * The production chat-reply and scheduled-push notifications are still
 * built inline in [SeekerZeroService] because they carry richer intent
 * state; this object just covers the "fire a fixed test" case.
 */
object NotificationHelper {

    private const val TEST_NOTIFICATION_ID_CHAT = 9001
    private const val TEST_NOTIFICATION_ID_SCHEDULED = 9002

    fun fireTest(context: Context, channelId: String) {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            channelId.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val (title, body) = when (channelId) {
            SeekerZeroApplication.CHANNEL_CHAT ->
                "Chat test" to "If you see this, chat-reply notifications are working."
            SeekerZeroApplication.CHANNEL_SCHEDULED ->
                "Scheduled test" to "If you see this, scheduled-delivery notifications are working."
            else ->
                "SeekerZero test" to "If you see this, notifications are working."
        }
        val notif = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val id = when (channelId) {
            SeekerZeroApplication.CHANNEL_CHAT -> TEST_NOTIFICATION_ID_CHAT
            SeekerZeroApplication.CHANNEL_SCHEDULED -> TEST_NOTIFICATION_ID_SCHEDULED
            else -> TEST_NOTIFICATION_ID_CHAT
        }
        nm.notify(id, notif)
    }
}
