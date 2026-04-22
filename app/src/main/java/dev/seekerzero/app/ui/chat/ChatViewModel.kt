package dev.seekerzero.app.ui.chat

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.seekerzero.app.api.MobileApiClient
import dev.seekerzero.app.api.models.ChatContext
import dev.seekerzero.app.chat.ChatMessageEntity
import dev.seekerzero.app.chat.ChatRepository
import dev.seekerzero.app.chat.PendingAttachment
import dev.seekerzero.app.chat.ToolActivity
import dev.seekerzero.app.chat.VoiceRecorder
import dev.seekerzero.app.chat.decodeAttachments
import dev.seekerzero.app.config.ConfigManager
import dev.seekerzero.app.util.LogCollector
import dev.seekerzero.app.util.ServiceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: ChatRepository = ChatRepository.get(app)
    private val recorder = VoiceRecorder(app)
    private val uploadJobs = mutableMapOf<String, Job>()

    val activeContextId: StateFlow<String> = ConfigManager.activeChatContextFlow
    val contexts: StateFlow<List<ChatContext>> = repo.remoteContexts

    val messages: StateFlow<List<ChatMessage>> = activeContextId
        .flatMapLatest { ctx -> repo.messages(ctx) }
        .map { list -> list.map { it.toUi() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val streaming: StateFlow<Boolean> = repo.streaming
    val activeTool: StateFlow<String?> = repo.activeTool
    val currentTurnTools: StateFlow<List<ToolActivity>> = repo.currentTurnTools

    private val _pendingAttachments = MutableStateFlow<List<PendingAttachment>>(emptyList())
    val pendingAttachments: StateFlow<List<PendingAttachment>> = _pendingAttachments.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    init {
        viewModelScope.launch { repo.refreshContexts() }
    }

    fun attach() = ServiceState.setChatAttached(true)
    fun detach() = ServiceState.setChatAttached(false)

    fun send(text: String) {
        val trimmed = text.trim()
        val ready = _pendingAttachments.value.filter { it.status == PendingAttachment.Status.READY }
        val paths = ready.mapNotNull { it.serverPath }
        if (trimmed.isEmpty() && paths.isEmpty()) return
        val ctx = activeContextId.value
        viewModelScope.launch {
            val result = repo.send(ctx, trimmed, paths)
            if (result.isSuccess) {
                // Drop the attachments we just sent; keep any that are still
                // uploading or failed so the user sees state for them.
                val consumedIds = ready.map { it.id }.toSet()
                _pendingAttachments.value =
                    _pendingAttachments.value.filterNot { it.id in consumedIds }
                // Best-effort clean up of any local cache files (voice notes).
                ready.mapNotNull { it.localCachePath }.forEach { path ->
                    runCatching { File(path).delete() }
                }
            }
        }
    }

    fun addAttachmentFromUri(uri: Uri) {
        val ctx = activeContextId.value
        val app = getApplication<Application>()
        val resolver = app.contentResolver
        val (displayName, size) = queryNameAndSize(uri)
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val pending = PendingAttachment(
            displayName = displayName,
            mime = mime,
            sizeBytes = size,
            localSource = uri,
        )
        _pendingAttachments.value = _pendingAttachments.value + pending
        startUpload(ctx, pending) {
            resolver.openInputStream(uri)
                ?: throw java.io.IOException("cannot open $uri")
        }
    }

    fun addAttachmentFromFile(file: File, mime: String, markAsLocalCache: Boolean) {
        val ctx = activeContextId.value
        val pending = PendingAttachment(
            displayName = file.name,
            mime = mime,
            sizeBytes = file.length(),
            localCachePath = if (markAsLocalCache) file.absolutePath else null,
            localSource = Uri.fromFile(file),
        )
        _pendingAttachments.value = _pendingAttachments.value + pending
        startUpload(ctx, pending) { file.inputStream() }
    }

    fun removeAttachment(id: String) {
        uploadJobs.remove(id)?.cancel()
        val toRemove = _pendingAttachments.value.firstOrNull { it.id == id }
        _pendingAttachments.value = _pendingAttachments.value.filterNot { it.id == id }
        toRemove?.localCachePath?.let { runCatching { File(it).delete() } }
    }

    private fun startUpload(
        contextId: String,
        initial: PendingAttachment,
        streamProvider: () -> InputStream
    ) {
        val job = viewModelScope.launch(Dispatchers.IO) {
            val part = MobileApiClient.UploadPart(
                filename = initial.displayName,
                mime = initial.mime,
                size = initial.sizeBytes,
                streamProvider = streamProvider
            )
            val result = MobileApiClient.uploadAttachments(
                contextId = contextId,
                parts = listOf(part),
                onProgress = { sent, _ ->
                    updatePending(initial.id) { it.copy(bytesSent = sent) }
                }
            )
            result.onSuccess { resp ->
                val up = resp.attachments.firstOrNull()
                if (up == null) {
                    updatePending(initial.id) {
                        it.copy(
                            status = PendingAttachment.Status.FAILED,
                            errorMessage = "server returned no attachment"
                        )
                    }
                } else {
                    updatePending(initial.id) {
                        it.copy(
                            status = PendingAttachment.Status.READY,
                            serverPath = up.path,
                            sizeBytes = if (it.sizeBytes > 0) it.sizeBytes else up.size,
                            bytesSent = if (it.sizeBytes > 0) it.sizeBytes else up.size
                        )
                    }
                }
            }.onFailure { err ->
                LogCollector.w("ChatViewModel", "upload failed: ${err.message}")
                updatePending(initial.id) {
                    it.copy(
                        status = PendingAttachment.Status.FAILED,
                        errorMessage = err.message ?: "upload failed"
                    )
                }
            }
            uploadJobs.remove(initial.id)
        }
        uploadJobs[initial.id] = job
    }

    private fun updatePending(id: String, transform: (PendingAttachment) -> PendingAttachment) {
        _pendingAttachments.value = _pendingAttachments.value.map {
            if (it.id == id) transform(it) else it
        }
    }

    private fun queryNameAndSize(uri: Uri): Pair<String, Long> {
        val resolver = getApplication<Application>().contentResolver
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                }
            }
        }
        return name to size
    }

    fun startRecording(): Boolean {
        if (_recording.value) return true
        recorder.start() ?: return false
        _recording.value = true
        return true
    }

    fun stopRecording() {
        if (!_recording.value) return
        val result = recorder.stop()
        _recording.value = false
        if (result != null) {
            val (file, _) = result
            addAttachmentFromFile(file, mime = "audio/mp4", markAsLocalCache = true)
        }
    }

    fun cancelRecording() {
        if (!_recording.value) return
        recorder.cancel()
        _recording.value = false
    }

    private var pendingCameraFile: File? = null
    private var pendingCameraMime: String = "image/jpeg"

    /**
     * Prepare a destination file + content URI for the system camera app.
     * Call this right before launching `TakePicture()` or `CaptureVideo()`.
     * The returned Uri is what the camera will write to; we keep the File
     * + mime internally so `onCameraCaptured` can stream it into the upload
     * pipeline with the right type.
     */
    fun prepareCameraCaptureUri(video: Boolean = false): Uri {
        val app = getApplication<Application>()
        val dir = File(app.cacheDir, "camera").apply { mkdirs() }
        val file = if (video) {
            File(dir, "VID_${System.currentTimeMillis()}.mp4")
        } else {
            File(dir, "IMG_${System.currentTimeMillis()}.jpg")
        }
        pendingCameraFile = file
        pendingCameraMime = if (video) "video/mp4" else "image/jpeg"
        return FileProvider.getUriForFile(
            app,
            "${app.packageName}.fileprovider",
            file
        )
    }

    fun onCameraCaptured(success: Boolean) {
        val file = pendingCameraFile
        val mime = pendingCameraMime
        pendingCameraFile = null
        if (!success || file == null || !file.exists() || file.length() == 0L) {
            runCatching { file?.delete() }
            return
        }
        addAttachmentFromFile(file, mime = mime, markAsLocalCache = true)
    }

    override fun onCleared() {
        super.onCleared()
        uploadJobs.values.forEach { it.cancel() }
        uploadJobs.clear()
        if (_recording.value) recorder.cancel()
    }

    private fun ChatMessageEntity.toUi() = ChatMessage(
        id = id,
        role = if (role == "user") ChatRole.USER else ChatRole.ASSISTANT,
        content = content,
        createdAtMs = created_at_ms,
        isFinal = is_final,
        attachments = decodeAttachments(attachments_json),
    )
}
