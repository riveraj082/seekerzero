package dev.seekerzero.app.ssh

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.seekerzero.app.util.LogCollector
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Device-local SSH keypair used to authenticate to a0prod.
 *
 * Private key lives in Android Keystore (alias `seekerzero_ssh_v1`) and
 * never leaves the device. The public key is surfaced as an OpenSSH
 * authorized_keys line so the user can paste it into a0prod's
 * ~/.ssh/authorized_keys once per device.
 *
 * Uses Ed25519: supported on Android 13+ (API 33+) with
 * KeyProperties.KEY_ALGORITHM_EC + ECGenParameterSpec("ed25519"). The
 * Seeker confirms support (min SDK is 34). Smaller keys, smaller
 * signatures, accepted by OpenSSH server out of the box.
 */
object SshKeyManager {

    private const val TAG = "SshKeyManager"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    const val KEY_ALIAS = "seekerzero_ssh_v1"
    const val SSH_KEY_TYPE = "ssh-ed25519"
    private const val SSH_KEY_COMMENT_DEFAULT = "seekerzero@phone"

    /** Returns the existing keypair if present, otherwise generates one. */
    fun getOrCreateKeyPair(): KeyPair {
        loadKeyPair()?.let { return it }
        return generate()
    }

    /**
     * OpenSSH authorized_keys line for this device:
     *   ssh-ed25519 <base64 wire-format> <comment>
     */
    fun getPublicKeyOpenSsh(comment: String = SSH_KEY_COMMENT_DEFAULT): String {
        val kp = getOrCreateKeyPair()
        val raw = extractEd25519RawPublicKey(kp.public)
        val wire = encodeEd25519Wire(raw)
        val b64 = Base64.encodeToString(wire, Base64.NO_WRAP)
        return "$SSH_KEY_TYPE $b64 $comment"
    }

    fun hasKey(): Boolean = loadKeyPair() != null

    /**
     * Raw 32-byte Ed25519 public key. Used to construct a wire-compatible
     * sshj public-key wrapper that sshj's KeyType.ED25519 can serialize.
     */
    fun getRawEd25519PublicKey(): ByteArray {
        val kp = getOrCreateKeyPair()
        return extractEd25519RawPublicKey(kp.public)
    }

    /**
     * Diagnostic: sign 32 arbitrary bytes with the Keystore-backed
     * Ed25519 key and report timing / success. Used to confirm whether
     * signing itself is hanging in Keystore (suspected on MediaTek
     * TrustZone implementations with incomplete Ed25519 support).
     * Logs to LogCollector so the result shows up in `adb logcat -s SshKeyManager:*`.
     */
    fun selfTestSign(): String {
        val kp = try {
            getOrCreateKeyPair()
        } catch (t: Throwable) {
            val msg = "FAIL (getOrCreateKeyPair): ${t.javaClass.simpleName}: ${t.message}"
            LogCollector.w(TAG, "selfTestSign $msg")
            return msg
        }

        val data = ByteArray(32).also { for (i in it.indices) it[i] = (i and 0xff).toByte() }

        val t0 = System.nanoTime()
        val sig = try {
            java.security.Signature.getInstance("Ed25519")
        } catch (t: Throwable) {
            val msg = "FAIL (getInstance Ed25519): ${t.javaClass.simpleName}: ${t.message}"
            LogCollector.w(TAG, "selfTestSign $msg")
            return msg
        }
        val t1 = System.nanoTime()

        try {
            sig.initSign(kp.private)
        } catch (t: Throwable) {
            val msg = "FAIL (initSign): ${t.javaClass.simpleName}: ${t.message}"
            LogCollector.w(TAG, "selfTestSign $msg")
            return msg
        }
        val t2 = System.nanoTime()

        try {
            sig.update(data)
        } catch (t: Throwable) {
            val msg = "FAIL (update): ${t.javaClass.simpleName}: ${t.message}"
            LogCollector.w(TAG, "selfTestSign $msg")
            return msg
        }
        val t3 = System.nanoTime()

        val signed = try {
            sig.sign()
        } catch (t: Throwable) {
            val msg = "FAIL (sign): ${t.javaClass.simpleName}: ${t.message}"
            LogCollector.w(TAG, "selfTestSign $msg")
            return msg
        }
        val t4 = System.nanoTime()

        val pretty = "OK sig=${signed.size}B " +
            "getInstance=${(t1 - t0) / 1_000_000}ms " +
            "initSign=${(t2 - t1) / 1_000_000}ms " +
            "update=${(t3 - t2) / 1_000_000}ms " +
            "sign=${(t4 - t3) / 1_000_000}ms"
        LogCollector.d(TAG, "selfTestSign $pretty")
        return pretty
    }

    private fun loadKeyPair(): KeyPair? {
        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            // Bypass ks.getEntry() — it does an algorithm consistency check
            // between the private key and the self-signed cert that wraps
            // the public key. Android Keystore's Ed25519 cert generation
            // has an OID-mapping quirk on some devices (MediaTek observed)
            // that fails this check even for perfectly functional keys.
            // Fetching the private key and certificate separately skips
            // the check and returns the same load-bearing objects.
            val priv = ks.getKey(KEY_ALIAS, null) as? PrivateKey ?: return null
            val cert = ks.getCertificate(KEY_ALIAS) ?: return null
            val pub: PublicKey = cert.publicKey
            KeyPair(pub, priv)
        } catch (t: Throwable) {
            LogCollector.w(TAG, "loadKeyPair failed: ${t.message}")
            null
        }
    }

    private fun generate(): KeyPair {
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("ed25519"))
            // Ed25519 has a built-in hash (SHA-512 internally); DIGEST_NONE
            // tells Keystore not to add its own digest step.
            .setDigests(KeyProperties.DIGEST_NONE)
            .build()

        val gen = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE
        )
        gen.initialize(spec)
        val kp = gen.generateKeyPair()
        LogCollector.d(TAG, "generated Ed25519 SSH keypair (alias=$KEY_ALIAS)")
        return kp
    }

    /**
     * Pull the 32-byte raw Ed25519 public key from an Android Keystore
     * PublicKey. The X.509-encoded SubjectPublicKeyInfo for Ed25519 is a
     * fixed 44-byte structure whose last 32 bytes are the raw key:
     *   SEQUENCE(0x30 0x2a, AlgorithmIdentifier(7 bytes, OID 1.3.101.112),
     *            BIT STRING(0x03 0x21 0x00, <32 raw bytes>))
     */
    internal fun extractEd25519RawPublicKey(pub: PublicKey): ByteArray {
        val encoded = pub.encoded
            ?: throw IllegalStateException("Keystore returned null-encoded public key")
        if (encoded.size < 32) {
            throw IllegalStateException("encoded public key shorter than 32 bytes: ${encoded.size}")
        }
        return encoded.copyOfRange(encoded.size - 32, encoded.size)
    }

    /**
     * Pack an Ed25519 raw public key into the OpenSSH wire format:
     *   string   "ssh-ed25519"
     *   string   <32 raw bytes>
     */
    private fun encodeEd25519Wire(raw32: ByteArray): ByteArray {
        require(raw32.size == 32) { "Ed25519 pubkey must be 32 bytes, got ${raw32.size}" }
        val out = ByteArrayOutputStream()
        out.writeSshString(SSH_KEY_TYPE.toByteArray(Charsets.US_ASCII))
        out.writeSshString(raw32)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeSshString(data: ByteArray) {
        val len = data.size
        write((len ushr 24) and 0xff)
        write((len ushr 16) and 0xff)
        write((len ushr 8) and 0xff)
        write(len and 0xff)
        write(data)
    }
}
