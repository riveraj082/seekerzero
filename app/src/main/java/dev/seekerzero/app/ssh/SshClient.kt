package dev.seekerzero.app.ssh

import dev.seekerzero.app.util.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.hierynomus.sshj.key.BaseKeyAlgorithm
import com.hierynomus.sshj.key.KeyAlgorithm
import com.hierynomus.sshj.signature.Ed25519PublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import java.io.ByteArrayOutputStream
import java.security.PrivateKey
import java.security.PublicKey
import java.util.concurrent.TimeUnit

/**
 * Minimal SSH client for SeekerZero — Phase 6b scope.
 *
 * Single connection at a time, key-based auth only, one-shot `exec`
 * pattern (no pty yet — that's 6c). Wraps sshj.SSHClient and plugs the
 * Android Keystore-backed Ed25519 key in via KeystoreKeyProvider +
 * a custom JcaEd25519Signature factory (because sshj's default Ed25519
 * signer can't use Keystore keys).
 *
 * Thread-safety: all sshj calls go through Dispatchers.IO. The callers
 * from ViewModels invoke these as suspend functions.
 *
 * Host-key verification: Phase 6b accepts any host key (PromiscuousVerifier)
 * to get the auth path validated first. Phase 6d will switch to
 * known_hosts TOFU backed by Room.
 */
object SshClient {

    private const val TAG = "SshClient"
    private const val CONNECT_TIMEOUT_MS = 10_000

    private var ssh: SSHClient? = null

    val isConnected: Boolean get() = ssh?.isConnected == true && ssh?.isAuthenticated == true

    suspend fun connect(host: String, port: Int = 22, user: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                disconnectInternal()

                val cfg = DefaultConfig().apply {
                    // Replace the built-in ssh-ed25519 KeyAlgorithm entry (which
                    // uses sshj's i2p-library-based signer) with one that wires
                    // our JCA-based signer in. sshj looks up the signer by key-
                    // algorithm name during auth; this is the only hook needed.
                    val algos = keyAlgorithms.toMutableList()
                    algos.removeAll { it.name == "ssh-ed25519" }
                    algos.add(0, KEYSTORE_ED25519_FACTORY)
                    keyAlgorithms = algos
                }

                val client = SSHClient(cfg).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    timeout = CONNECT_TIMEOUT_MS
                    addHostKeyVerifier(PromiscuousVerifier())
                }
                LogCollector.d(TAG, "connecting to $user@$host:$port")
                client.connect(host, port)

                val keyProvider = KeystoreKeyProvider()
                client.authPublickey(user, keyProvider)

                if (!client.isAuthenticated) {
                    client.disconnect()
                    throw RuntimeException("authentication failed")
                }

                ssh = client
                LogCollector.d(TAG, "connected + authenticated")
            }
        }.onFailure {
            LogCollector.w(TAG, "connect failed: ${it.javaClass.simpleName}: ${it.message}")
            // Unwind and log the cause chain to surface the actual provider
            // error (Android Keystore / BouncyCastle integration issues
            // usually hide their real message two levels deep).
            var cause: Throwable? = it.cause
            var depth = 1
            while (cause != null && depth < 5) {
                LogCollector.w(TAG, "  caused by [$depth]: ${cause.javaClass.simpleName}: ${cause.message}")
                cause = cause.cause
                depth++
            }
        }

    suspend fun exec(command: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val client = ssh ?: throw IllegalStateException("not connected")
            client.startSession().use { session ->
                val cmd = session.exec(command)
                val out = ByteArrayOutputStream()
                cmd.inputStream.copyTo(out)
                cmd.join(15, TimeUnit.SECONDS)
                val stderr = cmd.errorStream.bufferedReader().readText()
                val stdout = out.toString(Charsets.UTF_8)
                if (cmd.exitStatus != null && cmd.exitStatus != 0) {
                    LogCollector.w(TAG, "exec '$command' exit=${cmd.exitStatus} stderr=${stderr.take(200)}")
                }
                if (stderr.isNotEmpty()) stdout + "\n[stderr] " + stderr else stdout
            }
        }.onFailure { LogCollector.w(TAG, "exec failed: ${it.message}") }
    }

    fun disconnect() {
        disconnectInternal()
    }

    private fun disconnectInternal() {
        val existing = ssh ?: return
        runCatching { existing.disconnect() }
        ssh = null
    }

    /**
     * Adapter between sshj's KeyProvider and SshKeyManager's Keystore
     * keypair.
     *
     * Private key: Keystore-backed — signing routes through JCA's
     * Signature.getInstance("Ed25519") which delegates to Keystore
     * hardware. Our custom JcaEd25519Signature (registered as the
     * ssh-ed25519 KeyAlgorithm in the Config) is the bridge.
     *
     * Public key: i2p EdDSAPublicKey derived from the raw 32 bytes of
     * the Keystore public key, wrapped in sshj's Ed25519PublicKey helper.
     * We can't return the Keystore PublicKey directly: sshj's
     * KeyType.ED25519.writePubKeyContentsIntoBuffer casts to i2p's
     * EdDSAPublicKey, and Android Keystore PublicKey objects aren't of
     * that type (they're Conscrypt-backed and error out with
     * "Unsupported Android Keystore public key algorithm: ed25519"
     * before sshj even reaches its cast). The wrapper bypasses
     * Android's code path for the public-key serialization and lets
     * sshj's i2p-library path do what it already knows how to do.
     */
    private class KeystoreKeyProvider : KeyProvider {
        private val kp by lazy { SshKeyManager.getOrCreateKeyPair() }
        private val wrappedPublic: PublicKey by lazy {
            val raw = SshKeyManager.getRawEd25519PublicKey()
            val spec = EdDSAPublicKeySpec(raw, EdDSANamedCurveTable.ED_25519_CURVE_SPEC)
            Ed25519PublicKey(spec) as PublicKey
        }
        override fun getPrivate(): PrivateKey = kp.private
        override fun getPublic(): PublicKey = wrappedPublic
        override fun getType(): KeyType = KeyType.ED25519
    }

    /**
     * Factory.Named<KeyAlgorithm> that creates a BaseKeyAlgorithm tying
     * the "ssh-ed25519" key type to our JCA-based signer instead of the
     * sshj default. Drop-in replacement for
     * com.hierynomus.sshj.key.KeyAlgorithms.EdDSA25519().
     */
    private val KEYSTORE_ED25519_FACTORY: Factory.Named<KeyAlgorithm> =
        object : Factory.Named<KeyAlgorithm> {
            override fun getName(): String = "ssh-ed25519"
            override fun create(): KeyAlgorithm = BaseKeyAlgorithm(
                "ssh-ed25519",
                JcaEd25519Signature.Factory(),
                KeyType.ED25519
            )
        }
}
