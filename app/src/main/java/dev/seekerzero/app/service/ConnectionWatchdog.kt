package dev.seekerzero.app.service

import dev.seekerzero.app.util.LogCollector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlin.math.min
import kotlin.math.pow

/**
 * Exponential backoff with network-available short-circuit.
 *
 * Backoff schedule: 1s, 2s, 4s, 8s, 16s, 30s (capped).
 * Reset on successful contact.
 * If a ConnectivityManager.NetworkCallback signals onAvailable() during a
 * wait, the wait aborts immediately so the next retry runs now.
 */
class ConnectionWatchdog {

    companion object {
        private const val TAG = "ConnectionWatchdog"
        private const val BASE_MS = 1_000L
        private const val MAX_MS = 30_000L
        private const val FACTOR = 2.0
    }

    private var attempts = 0
    private val networkAvailable = Channel<Unit>(capacity = Channel.CONFLATED)

    fun resetBackoff() {
        if (attempts != 0) {
            LogCollector.d(TAG, "backoff reset after $attempts attempts")
            attempts = 0
        }
    }

    fun signalNetworkAvailable() {
        networkAvailable.trySend(Unit)
    }

    fun currentBackoffMs(): Long {
        val exp = BASE_MS * FACTOR.pow(attempts.toDouble())
        return min(exp.toLong(), MAX_MS)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun waitBeforeRetry() {
        val ms = currentBackoffMs()
        attempts++
        LogCollector.d(TAG, "backoff ${ms}ms (attempt $attempts)")
        select {
            onTimeout(ms) { /* backoff elapsed */ }
            networkAvailable.onReceive {
                LogCollector.d(TAG, "backoff short-circuited by network signal")
            }
        }
    }
}
