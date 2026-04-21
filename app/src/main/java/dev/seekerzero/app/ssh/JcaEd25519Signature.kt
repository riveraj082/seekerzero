package dev.seekerzero.app.ssh

import net.schmizz.sshj.signature.AbstractSignature
import net.schmizz.sshj.signature.Signature

/**
 * sshj `Signature` implementation for Ed25519 that signs and verifies via
 * Android's JCA Ed25519 service — specifically bypassing sshj's built-in
 * `SecurityUtils.getSignature` path, which forces BouncyCastle as the
 * provider once BC is registered. BC's Ed25519 implementation works
 * only with raw key bytes, so it cannot sign using a Keystore-backed
 * Ed25519 private key (those keys don't expose `getEncoded()`). Asking
 * JCA for `Signature.getInstance("Ed25519")` *without* a provider
 * preference finds Android's default AndroidKeyStore Ed25519 service
 * first, which *can* sign Keystore keys (confirmed by the self-test
 * that runs on Terminal tab entry: ~3–5 ms per sign).
 *
 * SSH wire format for ssh-ed25519 signatures:
 *   string  "ssh-ed25519"
 *   string  <64 raw bytes>
 * JCA's Ed25519 signature is exactly those 64 bytes, so encode() is a
 * passthrough and verify() just needs to strip the outer wire wrap.
 */
class JcaEd25519Signature : AbstractSignature(
    buildPlainJcaSignature(),
    "ssh-ed25519"
) {

    override fun encode(sig: ByteArray): ByteArray = sig

    override fun verify(H: ByteArray): Boolean {
        val rawSig = extractSig(H, "ssh-ed25519")
        return signature.verify(rawSig)
    }

    class Factory : net.schmizz.sshj.common.Factory.Named<Signature> {
        override fun create(): Signature = JcaEd25519Signature()
        override fun getName(): String = "ssh-ed25519"
    }
}

private fun buildPlainJcaSignature(): java.security.Signature {
    // Deliberately not going through sshj.SecurityUtils.getSignature —
    // that forces BC when BC is registered, and BC's Ed25519 can't sign
    // Keystore keys. Default JCA lookup finds AndroidKeyStore's Ed25519
    // service which delegates signing to the Keystore hardware.
    return java.security.Signature.getInstance("Ed25519")
}
