package dev.seekerzero.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.RingtoneManager
import dev.seekerzero.app.config.ConfigManager
import dev.seekerzero.app.util.LogCollector

class SeekerZeroApplication : Application() {

    companion object {
        const val CHANNEL_SERVICE = "seekerzero_service"
        const val CHANNEL_APPROVALS = "seekerzero_approvals"
    }

    override fun onCreate() {
        super.onCreate()
        LogCollector.init(this)
        ConfigManager.init(this)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            "Connection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent notification while SeekerZero is connected to Agent Zero."
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }

        val approvalsChannel = NotificationChannel(
            CHANNEL_APPROVALS,
            "Approvals",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "New approval gates raised by Agent Zero."
            setShowBadge(true)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                null
            )
            enableVibration(true)
        }

        nm.createNotificationChannel(serviceChannel)
        nm.createNotificationChannel(approvalsChannel)
    }
}
