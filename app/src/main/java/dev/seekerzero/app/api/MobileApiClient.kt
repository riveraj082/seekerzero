package dev.seekerzero.app.api

import dev.seekerzero.app.api.models.ApprovalsPendingResponse
import dev.seekerzero.app.api.models.ApprovalsStreamResponse
import dev.seekerzero.app.api.models.HealthResponse
import dev.seekerzero.app.config.ConfigManager
import dev.seekerzero.app.util.LogCollector
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object MobileApiClient {
    private const val TAG = "MobileApiClient"
    private const val STREAM_READ_TIMEOUT_S = 75L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private val streamClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(STREAM_READ_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun health(): Result<HealthResponse> = runCatching {
        val url = buildUrl("/health")
        LogCollector.d(TAG, "GET $url")
        val request = Request.Builder().url(url).get().build()
        val body = execute(client, request)
        json.decodeFromString(HealthResponse.serializer(), body)
    }.onFailure { LogCollector.w(TAG, "health() failed: ${it.message}") }

    suspend fun approvalsPending(): Result<ApprovalsPendingResponse> = runCatching {
        val url = buildUrl("/approvals/pending")
        LogCollector.d(TAG, "GET $url")
        val request = Request.Builder().url(url).get().build()
        val body = execute(client, request)
        json.decodeFromString(ApprovalsPendingResponse.serializer(), body)
    }.onFailure { LogCollector.w(TAG, "approvalsPending() failed: ${it.message}") }

    suspend fun approvalsStream(sinceMs: Long?): Result<ApprovalsStreamResponse> = runCatching {
        val base = buildUrl("/approvals/stream")
        val url = if (sinceMs != null) {
            base.toHttpUrl().newBuilder().addQueryParameter("since", sinceMs.toString()).build().toString()
        } else {
            base
        }
        LogCollector.d(TAG, "GET $url")
        val request = Request.Builder().url(url).get().build()
        val body = execute(streamClient, request)
        json.decodeFromString(ApprovalsStreamResponse.serializer(), body)
    }.onFailure { LogCollector.w(TAG, "approvalsStream() failed: ${it.message}") }

    private fun buildUrl(pathSuffix: String): String {
        val rawHost = ConfigManager.a0Host
            ?: throw IllegalStateException("a0_host not configured")
        val hostNoScheme = rawHost.removePrefix("http://").removePrefix("https://")
        val hostNoPort = hostNoScheme.substringBefore(':')
        val port = ConfigManager.port
        val base = ConfigManager.mobileApiBase.trimEnd('/')
        val suffix = if (pathSuffix.startsWith("/")) pathSuffix else "/$pathSuffix"
        return "http://$hostNoPort:$port$base$suffix"
    }

    private suspend fun execute(httpClient: OkHttpClient, request: Request): String =
        suspendCancellableCoroutine { cont ->
            val call = httpClient.newCall(request)
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
