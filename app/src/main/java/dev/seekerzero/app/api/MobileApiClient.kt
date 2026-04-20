package dev.seekerzero.app.api

import dev.seekerzero.app.api.models.HealthResponse
import dev.seekerzero.app.config.ConfigManager
import dev.seekerzero.app.util.LogCollector
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object MobileApiClient {
    private const val TAG = "MobileApiClient"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun health(): Result<HealthResponse> = runCatching {
        val url = buildUrl("/health")
        LogCollector.d(TAG, "GET $url")
        val request = Request.Builder().url(url).get().build()
        val body = execute(request)
        json.decodeFromString(HealthResponse.serializer(), body)
    }.onFailure { LogCollector.w(TAG, "health() failed: ${it.message}") }

    private fun buildUrl(pathSuffix: String): String {
        val host = ConfigManager.a0Host
            ?: throw IllegalStateException("a0_host not configured")
        val base = ConfigManager.mobileApiBase.trimEnd('/')
        val suffix = if (pathSuffix.startsWith("/")) pathSuffix else "/$pathSuffix"
        val scheme = if (host.startsWith("http://") || host.startsWith("https://")) "" else "http://"
        return "$scheme$host$base$suffix"
    }

    private suspend fun execute(request: Request): String =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        val body = r.body?.string().orEmpty()
                        if (!r.isSuccessful) {
                            cont.resumeWithException(
                                IOException("HTTP ${r.code} ${r.message}: ${body.take(200)}")
                            )
                            return
                        }
                        cont.resume(body)
                    }
                }
            })
        }
}
