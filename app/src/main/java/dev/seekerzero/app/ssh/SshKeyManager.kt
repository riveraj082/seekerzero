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

    private fun loadKeyPair(): KeyPair? {
        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val entry = ks.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
                ?: return null
            val priv: PrivateKey = entry.privateKey
            val pub: PublicKey = entry.certificate.publicKey
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
    private fun extractEd25519RawPublicKey(pub: PublicKey): ByteArray {
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
