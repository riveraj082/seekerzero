package dev.seekerzero.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import dev.seekerzero.app.config.ConfigManager
import dev.seekerzero.app.demo.DemoData
import dev.seekerzero.app.util.LogCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class SeekerZeroApplication : Application(), ImageLoaderFactory {

    companion object {
        const val CHANNEL_SERVICE = "seekerzero_service"
        // High-importance channel for inbound chat replies that arrive while
        // the app is backgrounded. Foreground deliveries are ignored (the
        // chat screen already shows them inline).
        const val CHANNEL_CHAT = "seekerzero_chat"
        // High-importance channel for scheduled-task results and any other
        // asynchronous push delivery that originates server-side (not a
        // direct response to something the user just sent).
        const val CHANNEL_SCHEDULED = "seekerzero_scheduled"
    }

    override fun onCreate() {
        super.onCreate()
        LogCollector.init(this)
        ConfigManager.init(this)
        installBouncyCastle()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Delete the Phase-1 approvals channel left over from earlier installs.
        // No-op on fresh installs.
        try { nm.deleteNotificationChannel("seekerzero_approvals") } catch (_: Throwable) {}

        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            "Connection",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Background connection to Agent Zero. Dismissable; service keeps running."
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }

        nm.createNotificationChannel(serviceChannel)

        val chatChannel = NotificationChannel(
            CHANNEL_CHAT,
            "Chat replies",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Agent Zero chat responses that arrive while the app is in the background."
            setShowBadge(true)
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        nm.createNotificationChannel(chatChannel)

        val scheduledChannel = NotificationChannel(
            CHANNEL_SCHEDULED,
            "Scheduled deliveries",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Results from scheduled Agent Zero tasks routed to this phone."
            setShowBadge(true)
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        nm.createNotificationChannel(scheduledChannel)

        // Demo mode: kick off a one-time avatar download on a background
        // coroutine. Safe if the network is unavailable — falls through to
        // the letter-fallback avatar.
        if (ConfigManager.demoMode) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                DemoData.provisionDemoAssets(this@SeekerZeroApplication)
            }
        }
    }

    /**
     * Coil's singleton loader. VideoFrameDecoder lets AsyncImage render a
     * frame from a video file/URI as the thumbnail.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()

    /**
     * Replace Android's stripped BouncyCastle stub (registered as "BC")
     * with the full bcprov-jdk18on we bundle. sshj requests X25519,
     * curve25519 KEX, and other primitives explicitly from provider "BC";
     * Android's stub doesn't have them, so without this the first
     * key-exchange attempt fails with "No such algorithm: X25519 for
     * provider BC". Removing the stub first and inserting the full BC
     * at position 1 makes sshj find the algorithms it needs.
     */
    private fun installBouncyCastle() {
        try {
            // Remove Android's stripped BC stub (if present) and add the
            // full bcprov-jdk18on at the END of the provider list. Order
            // matters here:
            //
            //   - sshj explicitly requests `Provider.getInstance(alg, "BC")`
            //     for curve25519 KEX + X25519 — that lookup is by provider
            //     NAME, so our BC wins regardless of position.
            //   - Android Keystore operations on Keystore-backed keys
            //     (like Ed25519 Signature.initSign) need to resolve through
            //     Android's default providers (AndroidKeyStore +
            //     AndroidKeyStoreBCWorkaround + Conscrypt). If we put BC at
            //     position 1, those operations hit BC first and error out
            //     with "Unsupported Android Keystore public key algorithm:
            //     ed25519" because BC can't work with opaque Keystore keys.
            //
            // Adding BC last gives us both behaviors.
            Security.removeProvider("BC")
            Security.addProvider(BouncyCastleProvider())
        } catch (t: Throwable) {
            LogCollector.w("BouncyCastle", "install failed: ${t.message}")
        }
    }
}
