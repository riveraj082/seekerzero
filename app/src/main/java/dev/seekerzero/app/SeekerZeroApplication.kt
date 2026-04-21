package dev.seekerzero.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.seekerzero.app.config.ConfigManager
import dev.seekerzero.app.util.LogCollector
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class SeekerZeroApplication : Application() {

    companion object {
        const val CHANNEL_SERVICE = "seekerzero_service"
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
    }

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
