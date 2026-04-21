package dev.seekerzero.app.api

import dev.seekerzero.app.api.models.ApprovalActionResponse
import dev.seekerzero.app.api.models.ApprovalsPendingResponse
import dev.seekerzero.app.api.models.ApprovalsStreamResponse
import dev.seekerzero.app.api.models.ChatContextsResponse
import dev.seekerzero.app.api.models.ChatHistoryResponse
import dev.seekerzero.app.api.models.ChatSendRequest
import dev.seekerzero.app.api.models.ChatSendResponse
import dev.seekerzero.app.api.models.HealthResponse
import dev.seekerzero.app.api.models.TaskActionResponse
import dev.seekerzero.app.api.models.TaskCreateRequest
import dev.seekerzero.app.api.models.TaskCreateResponse
import dev.seekerzero.app.api.models.TasksListResponse
import dev.seekerzero.app.config.ConfigManager
import dev.seekerzero.app.util.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.coroutineContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

    private val chatStreamClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS) // no timeout: NDJSON is long-lived with 25s keepalives
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

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

    suspend fun approvalsApprove(id: String): Result<ApprovalActionResponse> = postAction(id, "approve")

    suspend fun approvalsReject(id: String): Result<ApprovalActionResponse> = postAction(id, "reject")

    private suspend fun postAction(id: String, verb: String): Result<ApprovalActionResponse> = runCatching {
        val url = buildUrl("/approvals/$id/$verb")
        LogCollector.d(TAG, "POST $url")
        val request = Request.Builder()
            .url(url)
            .post("".toRequestBody())
            .build()
        val body = execute(client, request)
        json.decodeFromString(ApprovalActionResponse.serializer(), body)
    }.onFailure { LogCollector.w(TAG, "approvals $verb $id failed: ${it.message}") }

    suspend fun chatContexts(): Result<ChatContextsResponse> = runCatching {
        val url = buildUrl("/chat/contexts")
        LogCollector.d(TAG, "GET $url")
        val body = execute(client, Request.Builder().url(url).get().build())
        json.decodeFromString(ChatContextsResponse.serializer(), body)
    }.onFailure { LogCollector.w(TAG, "chatContexts() failed: ${it.message}") }

    suspend fun chatContextCreate(): Result<dev.seekerzero.app.api.models.ChatContextCreateResponse> = runCatching {
        val url = buildUrl("/chat/contexts")
        LogCollector.d(TAG, "POST $url")
        val body = execute(
            client,
            Request.Builder().url(url).post("{}".toRequestBody(jsonMediaType)).build()
        )
        json.decodeFromString(dev.seekerzero.app.api.models.ChatContextCreateResponse.serializer(), body)
    }.onFailure { LogCollector.w(TAG, "chatContextCreate() failed: ${it.message}") }

    suspend fun tasksScheduled(): Result<TasksListResponse> = runCatching {
        val url = buildUrl("/tasks/scheduled")
        LogCollector.d(TAG, "GET $url")
        val body = execute(client, Request.Builder().url(url).get().build())
        json.decodeFromString(TasksListResponse.serializer(), body)
    }.onFailure { LogCollector.w(TAG, "tasksScheduled() failed: ${it.message}") }

    suspend fun taskCreate(request: TaskCreateRequest): Result<TaskCreateResponse> = runCatching {
        val url = buildUrl("/tasks")
        val bodyJson = json.encodeToString(TaskCreateRequest.serializer(), request)
        LogCollector.d(TAG, "POST $url")
        val body = execute(
            client,
            Request.Builder().url(url).post(bodyJson.toRequestBody(jsonMediaType)).build()
        )
        json.decodeFromString(TaskCreateResponse.serializer(), body)
    }.onFailure { LogCollector.w(TAG, "taskCreate() failed: ${it.message}") }

    suspend fun taskRun(uuid: String): Result<TaskActionResponse> = runCatching {
        val url = buildUrl("/tasks/$uuid/run")
        LogCollector.d(TAG, "POST $url")
        val body = execute(
            client,
            Request.Builder().url(url).post("".toRequestBody(jsonMediaType)).build()
        )
        json.decodeFromString(TaskActionResponse.serializer(), body)
    }.onFailure { LogCollector.w(TAG, "taskRun($uuid) failed: ${it.message}") }

    suspend fun taskEnable(uuid: String): Result<TaskActionResponse> = runCatching {
        val url = buildUrl("/tasks/$uuid/enable")
        LogCollector.d(TAG, "POST $url")
        val body = execute(
            client,
            Request.Builder().url(url).post("".toRequestBody(jsonMediaType)).build()
        )
        json.decodeFromString(TaskActionResponse.serializer(), body)
    }.onFailure { LogCollector.w(TAG, "taskEnable($uuid) failed: ${it.message}") }

    suspend fun taskDisable(uuid: String): Result<TaskActionResponse> = runCatching {
        val url = buildUrl("/tasks/$uuid/disable")
        LogCollector.d(TAG, "POST $url")
        val body = execute(
            client,
            Request.Builder().url(url).post("".toRequestBody(jsonMediaType)).build()
        )
        json.decodeFromString(TaskActionResponse.serializer(), body)
    }.onFailure { LogCollector.w(TAG, "taskDisable($uuid) failed: ${it.message}") }

    suspend fun taskDelete(uuid: String): Result<Unit> = runCatching {
        val url = buildUrl("/tasks/$uuid")
        LogCollector.d(TAG, "DELETE $url")
        execute(client, Request.Builder().url(url).delete().build())
        Unit
    }.onFailure { LogCollector.w(TAG, "taskDelete($uuid) failed: ${it.message}") }

    suspend fun chatContextDelete(contextId: String): Result<Unit> = runCatching {
        val url = buildUrl("/chat/contexts/$contextId")
        LogCollector.d(TAG, "DELETE $url")
        execute(client, Request.Builder().url(url).delete().build())
        Unit
    }.onFailure { LogCollector.w(TAG, "chatContextDelete($contextId) failed: ${it.message}") }

    suspend fun chatHistory(
        contextId: String,
        beforeMs: Long? = null,
        limit: Int = 50
    ): Result<ChatHistoryResponse> = runCatching {
        val base = buildUrl("/chat/history").toHttpUrl().newBuilder()
            .addQueryParameter("context", contextId)
            .addQueryParameter("limit", limit.toString())
        if (beforeMs != null) base.addQueryParameter("before_ms", beforeMs.toString())
        val url = base.build().toString()
        LogCollector.d(TAG, "GET $url")
        val body = execute(client, Request.Builder().url(url).get().build())
        json.decodeFromString(ChatHistoryResponse.serializer(), body)
    }.onFailure { LogCollector.w(TAG, "chatHistory() failed: ${it.message}") }

    suspend fun chatSend(contextId: String, content: String): Result<ChatSendResponse> = runCatching {
        val url = buildUrl("/chat/send")
        val bodyJson = json.encodeToString(
            ChatSendRequest.serializer(),
            ChatSendRequest(context = contextId, content = content)
        )
        LogCollector.d(TAG, "POST $url")
        val body = execute(
            client,
            Request.Builder().url(url).post(bodyJson.toRequestBody(jsonMediaType)).build()
        )
        json.decodeFromString(ChatSendResponse.serializer(), body)
    }.onFailure { LogCollector.w(TAG, "chatSend() failed: ${it.message}") }

    /**
     * Open the NDJSON chat stream. Suspends until the connection ends or the coroutine is
     * cancelled. Runs on Dispatchers.IO; cancellation of the enclosing coroutine closes the
     * underlying OkHttp call.
     */
    suspend fun chatStream(
        contextId: String,
        sinceMs: Long,
        onEvent: suspend (JsonObject) -> Unit
    ) {
        val url = buildUrl("/chat/stream").toHttpUrl().newBuilder()
            .addQueryParameter("context", contextId)
            .addQueryParameter("since", sinceMs.toString())
            .build()
            .toString()
        LogCollector.d(TAG, "GET $url (stream)")

        val request = Request.Builder().url(url).get().build()
        val call = chatStreamClient.newCall(request)

        // Proactively cancel the blocking OkHttp read when the calling coroutine is cancelled.
        val cancelHandle = coroutineContext[kotlinx.coroutines.Job]
            ?.invokeOnCompletion { runCatching { call.cancel() } }

        try {
            withContext(Dispatchers.IO) {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code} on chat stream")
                    }
                    val source = response.body?.source()
                        ?: throw IOException("empty stream body")
                    while (coroutineContext.isActive && !call.isCanceled() && !source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (line.isBlank()) continue
                        val obj = runCatching { json.parseToJsonElement(line) }
                            .getOrNull() as? JsonObject ?: continue
                        onEvent(obj)
                    }
                }
            }
        } finally {
            cancelHandle?.dispose()
            runCatching { call.cancel() }
        }
    }

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
