package dev.seekerzero.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.seekerzero.app.config.ConfigManager
import dev.seekerzero.app.service.SeekerZeroService
import dev.seekerzero.app.util.LogCollector

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        LogCollector.init(context)
        ConfigManager.init(context)
        if (!ConfigManager.isConfigured()) {
            LogCollector.d(TAG, "boot: not configured, skipping service start")
            return
        }
        if (!ConfigManager.serviceEnabled) {
            LogCollector.d(TAG, "boot: service disabled by user, skipping")
            return
        }
        LogCollector.d(TAG, "boot: auto-starting SeekerZeroService")
        SeekerZeroService.start(context)
    }
}
