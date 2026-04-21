package dev.seekerzero.app.ssh

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Wraps androidx.biometric.BiometricPrompt for the Terminal tab gate.
 *
 * Result semantics: any successful biometric/device-credential auth
 * within the cache window (default 5 minutes) counts — the user isn't
 * re-prompted on tab re-entry. Cancellation returns [Result.Cancelled];
 * hard errors return [Result.Error]; the happy path is [Result.Success].
 *
 * Caching is process-local via a companion timestamp. If the app is
 * killed and relaunched, the cache is reset — biometric is required
 * again. This matches the invariant that the Terminal is always a
 * re-authed surface after any meaningful absence.
 */
object BiometricGate {

    private const val CACHE_WINDOW_MS = 5 * 60_000L

    @Volatile
    private var lastUnlockAtMs: Long = 0L

    sealed interface Result {
        data object Success : Result
        data object Cancelled : Result
        data class Error(val code: Int, val message: String) : Result
        data object Unavailable : Result
    }

    /** True if the user has passed biometric within [CACHE_WINDOW_MS]. */
    fun isUnlocked(): Boolean =
        System.currentTimeMillis() - lastUnlockAtMs < CACHE_WINDOW_MS

    fun invalidate() {
        lastUnlockAtMs = 0L
    }

    /**
     * Surface BiometricPrompt. Accepts device credential (PIN / pattern)
     * as a fallback on devices without biometric enrollment. Suspends
     * until the user completes or cancels. Must be called from an
     * Activity with a Lifecycle that can host the prompt — we require
     * a FragmentActivity (MainActivity is one since AppCompat / androidx
     * wraps ComponentActivity for BiometricPrompt's needs).
     *
     * On success, updates [lastUnlockAtMs] to "now".
     */
    suspend fun authenticate(activity: FragmentActivity): Result {
        if (isUnlocked()) return Result.Success

        val bm = BiometricManager.from(activity)
        val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val canAuth = bm.canAuthenticate(allowed)
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            return Result.Unavailable
        }

        return suspendCancellableCoroutine { cont ->
            val executor = androidx.core.content.ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        lastUnlockAtMs = System.currentTimeMillis()
                        if (cont.isActive) cont.resume(Result.Success)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        val res = when (errorCode) {
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                            BiometricPrompt.ERROR_CANCELED -> Result.Cancelled
                            else -> Result.Error(errorCode, errString.toString())
                        }
                        if (cont.isActive) cont.resume(res)
                    }

                    override fun onAuthenticationFailed() {
                        // Silent: user tried and failed; framework keeps the
                        // prompt open for another attempt.
                    }
                }
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Terminal")
                .setSubtitle("Confirm your identity to open an SSH session to a0prod.")
                .setAllowedAuthenticators(allowed)
                .build()
            prompt.authenticate(info)
        }
    }
}
