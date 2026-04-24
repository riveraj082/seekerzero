package dev.seekerzero.app.api

import dev.seekerzero.app.api.models.AttachmentsUploadResponse
import dev.seekerzero.app.api.models.ChatContextsResponse
import dev.seekerzero.app.api.models.ChatHistoryResponse
import dev.seekerzero.app.api.models.ChatSendRequest
import dev.seekerzero.app.api.models.ChatSendResponse
import dev.seekerzero.app.api.models.HealthResponse
import dev.seekerzero.app.api.models.PushAckRequest
import dev.seekerzero.app.api.models.PushAckResponse
import dev.seekerzero.app.api.models.PushPendingResponse
import dev.seekerzero.app.api.models.TaskActionResponse
import dev.seekerzero.app.api.models.TaskCreateRequest
import dev.seekerzero.app.api.models.TaskCreateResponse
import dev.seekerzero.app.api.models.TasksListResponse
import dev.seekerzero.app.config.ConfigManager
import dev.seekerzero.app.demo.DemoData
import dev.seekerzero.app.util.LogCollector
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.coroutineContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.buffer
import okio.source
import java.io.IOException
import java.io.InputStream
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
            // Enable OkHttp's transparent retry so stale pooled connections
            // (closed by the server's keep-alive timeout between our 20 s
            // health pings) don't bubble up as "unexpected end of stream"
            // errors to the Status tab.
            .retryOnConnectionFailure(true)
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

    private val uploadClient: OkHttpClient by lazy {
        client.newBuilder()
            // Large attachments over cellular + Tailscale can take minutes.
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            // Request body re-reads the file on retry; keep transparent retry on.
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * A single attachment part to upload. `streamProvider` may be called
     * more than once by OkHttp on retry, so it must return a fresh stream.
     * Pass `size = -1L` if the length is unknown (OkHttp will use chunked).
     */
    data class UploadPart(
        val filename: String,
        val mime: String,
        val size: Long,
        val streamProvider: () -> InputStream
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun health(): Result<HealthResponse> {
        if (ConfigManager.demoMode) return Result.success(DemoData.health())
        return runCatching {
            val url = buildUrl("/health")
            LogCollector.d(TAG, "GET $url")
            val request = Request.Builder().url(url).get().build()
            val body = execute(client, request)
            json.decodeFromString(HealthResponse.serializer(), body)
        }.onFailure { LogCollector.w(TAG, "health() failed: ${it.message}") }
    }

    suspend fun chatContexts(): Result<ChatContextsResponse> {
        if (ConfigManager.demoMode) return Result.success(DemoData.chatContexts())
        return runCatching {
            val url = buildUrl("/chat/contexts")
            LogCollector.d(TAG, "GET $url")
            val body = execute(client, Request.Builder().url(url).get().build())
            json.decodeFromString(ChatContextsResponse.serializer(), body)
        }.onFailure { LogCollector.w(TAG, "chatContexts() failed: ${it.message}") }
    }

    suspend fun chatContextCreate(): Result<dev.seekerzero.app.api.models.ChatContextCreateResponse> {
        if (ConfigManager.demoMode) return Result.success(DemoData.createContext())
        return runCatching {
            val url = buildUrl("/chat/contexts")
            LogCollector.d(TAG, "POST $url")
            val body = execute(
                client,
                Request.Builder().url(url).post("{}".toRequestBody(jsonMediaType)).build()
            )
            json.decodeFromString(dev.seekerzero.app.api.models.ChatContextCreateResponse.serializer(), body)
        }.onFailure { LogCollector.w(TAG, "chatContextCreate() failed: ${it.message}") }
    }

    suspend fun tasksScheduled(): Result<TasksListResponse> {
        if (ConfigManager.demoMode) return Result.success(DemoData.tasks())
        return runCatching {
            val url = buildUrl("/tasks/scheduled")
            LogCollector.d(TAG, "GET $url")
            val body = execute(client, Request.Builder().url(url).get().build())
            json.decodeFromString(TasksListResponse.serializer(), body)
        }.onFailure { LogCollector.w(TAG, "tasksScheduled() failed: ${it.message}") }
    }

    suspend fun taskCreate(request: TaskCreateRequest): Result<TaskCreateResponse> {
        if (ConfigManager.demoMode) return Result.success(DemoData.taskCreate(request.name, request.schedule))
        return runCatching {
            val url = buildUrl("/tasks")
            val bodyJson = json.encodeToString(TaskCreateRequest.serializer(), request)
            LogCollector.d(TAG, "POST $url")
            val body = execute(
                client,
                Request.Builder().url(url).post(bodyJson.toRequestBody(jsonMediaType)).build()
            )
            json.decodeFromString(TaskCreateResponse.serializer(), body)
        }.onFailure { LogCollector.w(TAG, "taskCreate() failed: ${it.message}") }
    }

    suspend fun taskRun(uuid: String): Result<TaskActionResponse> {
        if (ConfigManager.demoMode) return Result.success(DemoData.taskRun(uuid))
        return runCatching {
            val url = buildUrl("/tasks/$uuid/run")
            LogCollector.d(TAG, "POST $url")
            val body = execute(
                client,
                Request.Builder().url(url).post("".toRequestBody(jsonMediaType)).build()
            )
            json.decodeFromString(TaskActionResponse.serializer(), body)
        }.onFailure { LogCollector.w(TAG, "taskRun($uuid) failed: ${it.message}") }
    }

    suspend fun taskEnable(uuid: String): Result<TaskActionResponse> {
        if (ConfigManager.demoMode) return Result.success(DemoData.taskToggle(uuid, enabled = true))
        return runCatching {
            val url = buildUrl("/tasks/$uuid/enable")
            LogCollector.d(TAG, "POST $url")
            val body = execute(
                client,
                Request.Builder().url(url).post("".toRequestBody(jsonMediaType)).build()
            )
            json.decodeFromString(TaskActionResponse.serializer(), body)
        }.onFailure { LogCollector.w(TAG, "taskEnable($uuid) failed: ${it.message}") }
    }

    suspend fun taskDisable(uuid: String): Result<TaskActionResponse> {
        if (ConfigManager.demoMode) return Result.success(DemoData.taskToggle(uuid, enabled = false))
        return runCatching {
            val url = buildUrl("/tasks/$uuid/disable")
            LogCollector.d(TAG, "POST $url")
            val body = execute(
                client,
                Request.Builder().url(url).post("".toRequestBody(jsonMediaType)).build()
            )
            json.decodeFromString(TaskActionResponse.serializer(), body)
        }.onFailure { LogCollector.w(TAG, "taskDisable($uuid) failed: ${it.message}") }
    }

    /**
     * Long-poll the server-side push queue. Returns as soon as any
     * undelivered item exists, or after ~55s with an empty list. Uses
     * [streamClient] (75s read timeout) so the socket stays open across
     * the server's full long-poll window.
     */
    suspend fun pushPending(sinceId: Long): Result<PushPendingResponse> {
        if (ConfigManager.demoMode) return Result.success(PushPendingResponse(ok = true, items = emptyList()))
        return runCatching {
            val url = buildUrl("/push/pending").toHttpUrl().newBuilder()
                .addQueryParameter("since_id", sinceId.toString())
                .build()
                .toString()
            LogCollector.d(TAG, "GET $url")
            val body = execute(streamClient, Request.Builder().url(url).get().build())
            json.decodeFromString(PushPendingResponse.serializer(), body)
        }.onFailure { LogCollector.w(TAG, "pushPending() failed: ${it.message}") }
    }

    suspend fun pushAck(ids: List<Long>): Result<PushAckResponse> {
        if (ConfigManager.demoMode) return Result.success(PushAckResponse(ok = true, acked = ids.size))
        if (ids.isEmpty()) return Result.success(PushAckResponse(ok = true, acked = 0))
        return runCatching {
            val url = buildUrl("/push/ack")
            val bodyJson = json.encodeToString(PushAckRequest.serializer(), PushAckRequest(ids))
            LogCollector.d(TAG, "POST $url (${ids.size} ids)")
            val body = execute(
                client,
                Request.Builder().url(url).post(bodyJson.toRequestBody(jsonMediaType)).build()
            )
            json.decodeFromString(PushAckResponse.serializer(), body)
        }.onFailure { LogCollector.w(TAG, "pushAck() failed: ${it.message}") }
    }

    suspend fun taskDelete(uuid: String): Result<Unit> {
        if (ConfigManager.demoMode) { DemoData.taskDelete(uuid); return Result.success(Unit) }
        return runCatching {
            val url = buildUrl("/tasks/$uuid")
            LogCollector.d(TAG, "DELETE $url")
            execute(client, Request.Builder().url(url).delete().build())
            Unit
        }.onFailure { LogCollector.w(TAG, "taskDelete($uuid) failed: ${it.message}") }
    }

    suspend fun chatContextDelete(contextId: String): Result<Unit> {
        if (ConfigManager.demoMode) { DemoData.deleteContext(contextId); return Result.success(Unit) }
        return runCatching {
            val url = buildUrl("/chat/contexts/$contextId")
            LogCollector.d(TAG, "DELETE $url")
            execute(client, Request.Builder().url(url).delete().build())
            Unit
        }.onFailure { LogCollector.w(TAG, "chatContextDelete($contextId) failed: ${it.message}") }
    }

    suspend fun chatHistory(
        contextId: String,
        beforeMs: Long? = null,
        limit: Int = 50
    ): Result<ChatHistoryResponse> {
        if (ConfigManager.demoMode) return Result.success(DemoData.chatHistory(contextId))
        return runCatching {
            val base = buildUrl("/chat/history").toHttpUrl().newBuilder()
                .addQueryParameter("context", contextId)
                .addQueryParameter("limit", limit.toString())
            if (beforeMs != null) base.addQueryParameter("before_ms", beforeMs.toString())
            val url = base.build().toString()
            LogCollector.d(TAG, "GET $url")
            val body = execute(client, Request.Builder().url(url).get().build())
            json.decodeFromString(ChatHistoryResponse.serializer(), body)
        }.onFailure { LogCollector.w(TAG, "chatHistory() failed: ${it.message}") }
    }

    @OptIn(DelicateCoroutinesApi::class)
    suspend fun chatSend(
        contextId: String,
        content: String,
        attachments: List<String> = emptyList()
    ): Result<ChatSendResponse> {
        if (ConfigManager.demoMode) {
            val resp = DemoData.chatSend(contextId, content)
            GlobalScope.launch {
                DemoData.emitChatEvent(DemoData.userMsgEvent(resp.userMessageId, content))
                delay(300)
                val reply = DemoData.fakeReplyFor(content)
                val chunks = reply.split(' ')
                for (token in chunks) {
                    delay(50)
                    DemoData.emitChatEvent(DemoData.deltaEvent(resp.assistantMessageId, "$token "))
                }
                delay(120)
                DemoData.emitChatEvent(DemoData.finalEvent(resp.assistantMessageId, reply))
            }
            return Result.success(resp)
        }
        return runCatching {
            val url = buildUrl("/chat/send")
            val bodyJson = json.encodeToString(
                ChatSendRequest.serializer(),
                ChatSendRequest(context = contextId, content = content, attachments = attachments)
            )
            LogCollector.d(TAG, "POST $url")
            val body = execute(
                client,
                Request.Builder().url(url).post(bodyJson.toRequestBody(jsonMediaType)).build()
            )
            json.decodeFromString(ChatSendResponse.serializer(), body)
        }.onFailure { LogCollector.w(TAG, "chatSend() failed: ${it.message}") }
    }

    /**
     * Upload one or more attachments for a chat context. Returns the server
     * paths to include in the next `chatSend(..., attachments = ...)`.
     *
     * `onProgress` is called from the upload thread with (bytesSent, totalBytes).
     * totalBytes may be -1 if any part has unknown length.
     */
    suspend fun uploadAttachments(
        contextId: String,
        parts: List<UploadPart>,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Result<AttachmentsUploadResponse> {
        if (parts.isEmpty()) {
            return Result.failure(IllegalArgumentException("no parts to upload"))
        }
        if (ConfigManager.demoMode) {
            return Result.success(DemoData.uploadAttachments(contextId, parts))
        }
        return runCatching {
            val url = buildUrl("/chat/attachments")
            val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("context", contextId)
            var totalBytes = 0L
            var knownTotal = true
            for (part in parts) {
                if (part.size < 0) knownTotal = false else totalBytes += part.size
                val mediaType = runCatching { part.mime.toMediaType() }.getOrNull()
                    ?: "application/octet-stream".toMediaType()
                builder.addFormDataPart(
                    "files",
                    part.filename,
                    streamingBody(part, mediaType)
                )
            }
            val multipart = builder.build()
            val reportedTotal = if (knownTotal) totalBytes else -1L
            val progressWrapped = if (onProgress != null) {
                ProgressRequestBody(multipart, reportedTotal, onProgress)
            } else {
                multipart
            }
            LogCollector.d(TAG, "POST $url (${parts.size} file(s))")
            val body = execute(
                uploadClient,
                Request.Builder().url(url).post(progressWrapped).build()
            )
            json.decodeFromString(AttachmentsUploadResponse.serializer(), body)
        }.onFailure { LogCollector.w(TAG, "uploadAttachments() failed: ${it.message}") }
    }

    private fun streamingBody(part: UploadPart, mediaType: MediaType): RequestBody =
        object : RequestBody() {
            override fun contentType(): MediaType = mediaType
            override fun contentLength(): Long = part.size
            override fun writeTo(sink: BufferedSink) {
                part.streamProvider().use { input ->
                    input.source().use { source -> sink.writeAll(source) }
                }
            }
        }

    private class ProgressRequestBody(
        private val delegate: RequestBody,
        private val totalHint: Long,
        private val onProgress: (Long, Long) -> Unit
    ) : RequestBody() {
        override fun contentType(): MediaType? = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()
        override fun writeTo(sink: BufferedSink) {
            val total = if (totalHint >= 0) totalHint else delegate.contentLength()
            val wrapper = object : okio.ForwardingSink(sink) {
                var sent = 0L
                override fun write(source: okio.Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    sent += byteCount
                    onProgress(sent, total)
                }
            }
            val bufferedWrapper = wrapper.buffer()
            delegate.writeTo(bufferedWrapper)
            bufferedWrapper.flush()
        }
    }

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
        if (ConfigManager.demoMode) {
            // Subscribe to the in-memory demo bus until the caller cancels.
            DemoData.chatBus.collect { onEvent(it) }
            return
        }
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

    /**
     * Build a download URL for a server-side attachment. `attachmentPath` is
     * the absolute filesystem path A0 sees (e.g. returned by uploadAttachments
     * or replayed via chat history). Returns null if the path doesn't look
     * like an attachment under a mobile context.
     */
    fun attachmentUrl(attachmentPath: String): String? {
        // Demo mode: the synthetic /demo/... paths seeded into chat history
        // map straight to public CDN URLs (no tailnet, no a0prod involved).
        if (ConfigManager.demoMode) {
            DemoData.demoAssetUrls[attachmentPath]?.let { return it }
        }
        val parts = attachmentPath.trim().trimEnd('/').split('/')
        if (parts.size < 2) return null
        val filename = parts.last()
        val contextId = parts[parts.size - 2]
        if (filename.isBlank() || contextId.isBlank()) return null
        val encodedName = java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
        val encodedCtx = java.net.URLEncoder.encode(contextId, "UTF-8").replace("+", "%20")
        return buildUrl("/chat/attachments/$encodedCtx/$encodedName")
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
