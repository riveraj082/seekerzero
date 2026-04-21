package dev.seekerzero.app.ssh

import android.content.Context
import dev.seekerzero.app.chat.ChatDatabase
import dev.seekerzero.app.util.LogCollector
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.PublicKey
import java.security.MessageDigest

/**
 * TOFU host-key verifier backed by the Room `ssh_known_hosts` table.
 *
 * First-connect behavior: pin whatever key the server presents. Every
 * subsequent connect to the same (host, port, key_type) must match the
 * pinned fingerprint, or the connection is rejected.
 *
 * This replaces sshj's PromiscuousVerifier that we used in 6b — under
 * that, every host-key was accepted, which is fine for bootstrap but
 * means a man-in-the-middle on the tailnet (unlikely but nonzero) would
 * see the session. With TOFU, the first connect establishes trust; any
 * subsequent MITM must either have the same private key (impossible
 * without compromising a0prod) or be caught by the fingerprint check.
 */
class KnownHostsVerifier(context: Context) : HostKeyVerifier {

    private val dao = ChatDatabase.get(context.applicationContext).knownHostDao()

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val keyType = KeyType.fromKey(key).toString()
        val fingerprint = fingerprintOf(key)
        val existing = runBlocking { dao.find(hostname, port, keyType) }
        return if (existing == null) {
            runBlocking {
                dao.upsert(
                    KnownHostEntity(
                        host = hostname,
                        port = port,
                        key_type = keyType,
                        fingerprint = fingerprint,
                        first_seen_ms = System.currentTimeMillis()
                    )
                )
            }
            LogCollector.d(TAG, "TOFU pinned $hostname:$port $keyType $fingerprint")
            true
        } else {
            val match = existing.fingerprint.equals(fingerprint, ignoreCase = false)
            if (!match) {
                LogCollector.w(
                    TAG,
                    "host key MISMATCH for $hostname:$port $keyType — " +
                        "pinned=${existing.fingerprint} presented=$fingerprint"
                )
            }
            match
        }
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> {
        // sshj calls this to negotiate which host-key types the server should
        // send. Return empty to let sshj use its defaults — we're not yet
        // opinionated about algorithm preference.
        return emptyList()
    }

    companion object {
        private const val TAG = "KnownHostsVerifier"

        /** SHA-256 fingerprint of a public key, hex-encoded. */
        fun fingerprintOf(key: PublicKey): String {
            val encoded = key.encoded ?: return "unknown"
            val sha = MessageDigest.getInstance("SHA-256").digest(encoded)
            return sha.joinToString("") { "%02x".format(it) }
        }
    }
}
