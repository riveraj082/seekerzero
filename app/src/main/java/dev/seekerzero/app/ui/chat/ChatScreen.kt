package dev.seekerzero.app.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import dev.seekerzero.app.api.MobileApiClient
import dev.seekerzero.app.chat.AudioPlaybackController
import dev.seekerzero.app.config.ConfigManager
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.seekerzero.app.R
import dev.seekerzero.app.chat.PendingAttachment
import dev.seekerzero.app.ui.components.CardSurface
import dev.seekerzero.app.ui.components.SeekerZeroScaffold
import dev.seekerzero.app.ui.theme.SeekerZeroColors
import kotlinx.coroutines.delay

@Composable
fun ChatScreen(
    onMenu: () -> Unit = {},
    viewModel: ChatViewModel = viewModel()
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val streaming by viewModel.streaming.collectAsStateWithLifecycle()
    val activeTool by viewModel.activeTool.collectAsStateWithLifecycle()
    val turnTools by viewModel.currentTurnTools.collectAsStateWithLifecycle()
    val contexts by viewModel.contexts.collectAsStateWithLifecycle()
    val activeContextId by viewModel.activeContextId.collectAsStateWithLifecycle()
    val pendingAttachments by viewModel.pendingAttachments.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        viewModel.attach()
        onDispose { viewModel.detach() }
    }

    val lastAssistantEmpty = messages.lastOrNull()?.let {
        it.role == ChatRole.ASSISTANT && it.content.isEmpty()
    } ?: false
    val showTimeline = streaming && turnTools.isNotEmpty()
    val showPill = streaming && !showTimeline && (activeTool != null || lastAssistantEmpty)

    val activeTitle = contexts.firstOrNull { it.id == activeContextId }?.displayName
        ?: stringResource(R.string.tab_chat)

    SeekerZeroScaffold(title = activeTitle, onMenu = onMenu) { pad ->
        Column(modifier = Modifier.fillMaxSize().padding(pad)) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (messages.isEmpty()) {
                    EmptyState()
                } else {
                    MessageList(messages = messages)
                }
            }
            if (showTimeline || showPill) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Avatar(isUser = false)
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        if (showTimeline) {
                            ToolTimeline(tools = turnTools)
                        } else {
                            ActivityPill(toolName = activeTool)
                        }
                    }
                }
            }
            Composer(
                enabled = !streaming,
                pendingAttachments = pendingAttachments,
                recording = recording,
                onPickFile = { uri -> viewModel.addAttachmentFromUri(uri) },
                onRemoveAttachment = { viewModel.removeAttachment(it) },
                onStartRecording = { viewModel.startRecording() },
                onStopRecording = { viewModel.stopRecording() },
                onCancelRecording = { viewModel.cancelRecording() },
                onPrepareCamera = { video -> viewModel.prepareCameraCaptureUri(video) },
                onCameraResult = { viewModel.onCameraCaptured(it) },
                onSend = { viewModel.send(it) }
            )
        }
    }
}

@Composable
private fun ToolTimeline(tools: List<dev.seekerzero.app.chat.ToolActivity>) {
    CardSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            tools.forEachIndexed { index, t ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${index + 1}.",
                        color = SeekerZeroColors.TextDisabled,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = t.toolName,
                        color = SeekerZeroColors.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.weight(1f))
                    val durLabel = when {
                        t.inFlight -> "running…"
                        t.durationMs != null -> formatDuration(t.durationMs!!)
                        else -> ""
                    }
                    Text(
                        text = durLabel,
                        color = if (t.inFlight) SeekerZeroColors.Primary else SeekerZeroColors.TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    if (ms < 1000) return "${ms}ms"
    val s = ms / 1000.0
    return if (s < 10) "%.1fs".format(s) else "${s.toInt()}s"
}

@Composable
private fun ActivityPill(toolName: String?) {
    val label = if (toolName != null) "Using $toolName…" else "Thinking…"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(SeekerZeroColors.SurfaceVariant)
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = label,
                color = SeekerZeroColors.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun MessageList(messages: List<ChatMessage>) {
    val visible = messages.filterNot {
        it.role == ChatRole.ASSISTANT && !it.isFinal && it.content.isEmpty()
    }
    val listState = rememberLazyListState()

    LaunchedEffect(visible.size, visible.lastOrNull()?.content) {
        if (visible.isNotEmpty()) {
            listState.animateScrollToItem(visible.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(visible, key = { it.id }) { message ->
            MessageBubble(message = message)
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == ChatRole.USER
    val bubbleColor = if (isUser) SeekerZeroColors.SurfaceVariant else SeekerZeroColors.Surface
    val hasText = message.content.isNotEmpty() || !message.isFinal
    val hasAttachments = message.attachments.isNotEmpty()
    val columnAlignment = if (isUser) Alignment.End else Alignment.Start

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            Avatar(isUser = false)
            Spacer(Modifier.width(8.dp))
        }
        Column(
            modifier = Modifier.widthIn(max = 280.dp),
            horizontalAlignment = columnAlignment
        ) {
            if (hasText) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(bubbleColor)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    val displayContent = if (!message.isFinal && message.content.isEmpty()) {
                        "…"
                    } else if (!message.isFinal) {
                        message.content + "▍"
                    } else {
                        message.content
                    }
                    Text(
                        text = displayContent,
                        color = SeekerZeroColors.TextPrimary,
                        fontSize = 14.sp
                    )
                }
            }
            if (hasAttachments) {
                Column(
                    modifier = Modifier.padding(top = if (hasText) 6.dp else 0.dp),
                    horizontalAlignment = columnAlignment
                ) {
                    message.attachments.forEach { a ->
                        HistoricalAttachmentChip(a)
                    }
                }
            }
        }
        if (isUser) {
            Spacer(Modifier.width(8.dp))
            Avatar(isUser = true)
        }
    }
}

@Composable
private fun Avatar(isUser: Boolean) {
    val avatarPath by ConfigManager.userAvatarPathFlow.collectAsStateWithLifecycle()
    val displayName by ConfigManager.displayNameFlow.collectAsStateWithLifecycle()
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(SeekerZeroColors.SurfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (isUser) {
            if (avatarPath != null) {
                val file = remember(avatarPath) { java.io.File(avatarPath!!) }
                AsyncImage(
                    model = file,
                    contentDescription = "you",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                val letter = displayName?.takeIf { it.isNotBlank() }?.trim()
                    ?.first()?.uppercase() ?: "Y"
                Text(
                    text = letter,
                    color = SeekerZeroColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        } else {
            AsyncImage(
                model = R.mipmap.ic_launcher,
                contentDescription = "Agent Zero",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun HistoricalAttachmentChip(a: dev.seekerzero.app.chat.ChatAttachmentRef) {
    when {
        a.mime.startsWith("image/") -> ImageAttachment(a)
        a.mime.startsWith("audio/") -> AudioAttachment(a)
        a.mime.startsWith("video/") -> VideoAttachment(a)
        else -> FileAttachmentChip(a)
    }
}

@Composable
private fun VideoAttachment(a: dev.seekerzero.app.chat.ChatAttachmentRef) {
    val url = remember(a.path) { MobileApiClient.attachmentUrl(a.path) }
    var showPlayer by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SeekerZeroColors.SurfaceVariant)
            .size(width = 220.dp, height = 220.dp)
            .clickable(enabled = url != null) { showPlayer = true }
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = a.filename,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(52.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(SeekerZeroColors.Surface.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "play",
                tint = SeekerZeroColors.TextPrimary,
                modifier = Modifier.size(32.dp)
            )
        }
    }
    if (showPlayer && url != null) {
        VideoPlayerDialog(url = url, onDismiss = { showPlayer = false })
    }
}

@Composable
private fun VideoPlayerDialog(url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val exo = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) {
        onDispose { exo.release() }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SeekerZeroColors.Surface)
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exo
                        useController = true
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "close",
                    tint = SeekerZeroColors.TextPrimary
                )
            }
        }
    }
}

@Composable
private fun AudioAttachment(a: dev.seekerzero.app.chat.ChatAttachmentRef) {
    val url = remember(a.path) { MobileApiClient.attachmentUrl(a.path) }
    val playerState by AudioPlaybackController.state.collectAsStateWithLifecycle()
    val isThis = playerState.id == a.path
    val isPlaying = isThis && playerState.isPlaying

    // Tick the playback position while this tile owns the player.
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            AudioPlaybackController.tickPosition()
            delay(200)
        }
    }

    val durationLabel = when {
        isThis && playerState.durationMs > 0 ->
            "${formatClock(playerState.positionMs)} / ${formatClock(playerState.durationMs)}"
        a.size > 0 -> formatSize(a.size)
        else -> "—"
    }
    val fraction: Float = if (isThis && playerState.durationMs > 0) {
        (playerState.positionMs.toFloat() / playerState.durationMs).coerceIn(0f, 1f)
    } else 0f

    Row(
        modifier = Modifier
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SeekerZeroColors.SurfaceVariant)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .width(260.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = {
                val u = url ?: return@IconButton
                if (!isThis) AudioPlaybackController.play(a.path, u)
                else AudioPlaybackController.pauseOrResume(a.path)
            },
            enabled = url != null,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "pause" else "play",
                tint = if (url != null) SeekerZeroColors.Primary else SeekerZeroColors.TextDisabled
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = a.filename,
                color = SeekerZeroColors.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Spacer(Modifier.height(3.dp))
            LinearProgressIndicator(
                progress = { fraction },
                color = SeekerZeroColors.Primary,
                trackColor = SeekerZeroColors.Surface,
                modifier = Modifier.fillMaxWidth().height(3.dp)
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = durationLabel,
                color = SeekerZeroColors.TextSecondary,
                fontSize = 10.sp
            )
        }
    }
}

private enum class CameraMode { PHOTO, VIDEO }

private fun formatClock(ms: Int): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val mm = s / 60
    val ss = s % 60
    return "%d:%02d".format(mm, ss)
}

@Composable
private fun ImageAttachment(a: dev.seekerzero.app.chat.ChatAttachmentRef) {
    val url = remember(a.path) { MobileApiClient.attachmentUrl(a.path) }
    var showFull by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SeekerZeroColors.SurfaceVariant)
            .size(width = 220.dp, height = 220.dp)
            .clickable(enabled = url != null) { showFull = true }
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = a.filename,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // No host configured (demo mode or setup incomplete) — fall back
            // to the plain file tile so the bubble isn't a blank square.
            FileAttachmentChip(a)
        }
    }
    if (showFull && url != null) {
        Dialog(
            onDismissRequest = { showFull = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(SeekerZeroColors.Surface)
                    .clickable { showFull = false },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = url,
                    contentDescription = a.filename,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun FileAttachmentChip(a: dev.seekerzero.app.chat.ChatAttachmentRef) {
    Row(
        modifier = Modifier
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SeekerZeroColors.SurfaceVariant)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = kindLabel(a.mime),
            color = SeekerZeroColors.TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(end = 8.dp)
        )
        Column {
            Text(
                text = a.filename,
                color = SeekerZeroColors.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = formatSize(a.size),
                color = SeekerZeroColors.TextSecondary,
                fontSize = 10.sp
            )
        }
    }
}

private fun kindLabel(mime: String): String = when {
    mime.startsWith("image/") -> "IMAGE"
    mime.startsWith("audio/") -> "AUDIO"
    mime.startsWith("video/") -> "VIDEO"
    mime == "application/pdf" -> "PDF"
    else -> "FILE"
}

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = stringResource(R.string.chat_empty_title),
                color = SeekerZeroColors.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.chat_empty_body),
                color = SeekerZeroColors.TextSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun Composer(
    enabled: Boolean,
    pendingAttachments: List<PendingAttachment>,
    recording: Boolean,
    onPickFile: (android.net.Uri) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onPrepareCamera: (Boolean) -> android.net.Uri,
    onCameraResult: (Boolean) -> Unit,
    onSend: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    val hint = stringResource(R.string.chat_composer_hint)
    val sendCd = stringResource(R.string.chat_send)
    val attachCd = stringResource(R.string.chat_attach)
    val micStartCd = stringResource(R.string.chat_mic_start)
    val micStopCd = stringResource(R.string.chat_mic_stop)

    val context = LocalContext.current

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? -> if (uri != null) onPickFile(uri) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) onStartRecording() }

    val photoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success -> onCameraResult(success) }

    val videoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CaptureVideo()
    ) { success -> onCameraResult(success) }

    var pendingCameraMode by remember { mutableStateOf<CameraMode?>(null) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            when (pendingCameraMode) {
                CameraMode.PHOTO -> photoLauncher.launch(onPrepareCamera(false))
                CameraMode.VIDEO -> videoLauncher.launch(onPrepareCamera(true))
                null -> Unit
            }
        }
        pendingCameraMode = null
    }

    var cameraMenuOpen by remember { mutableStateOf(false) }

    val hasReadyAttachment = pendingAttachments.any {
        it.status == PendingAttachment.Status.READY
    }
    val hasUploadInFlight = pendingAttachments.any {
        it.status == PendingAttachment.Status.UPLOADING
    }
    val canSend = enabled && !recording && !hasUploadInFlight &&
        (text.isNotBlank() || hasReadyAttachment)

    CardSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {

            if (recording) {
                RecordingPill(
                    onStop = onStopRecording,
                    onCancel = onCancelRecording
                )
            } else if (pendingAttachments.isNotEmpty()) {
                AttachmentStrip(
                    attachments = pendingAttachments,
                    onRemove = onRemoveAttachment
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        if (recording) return@IconButton
                        val permission = Manifest.permission.RECORD_AUDIO
                        val granted = ContextCompat.checkSelfPermission(context, permission) ==
                            PackageManager.PERMISSION_GRANTED
                        if (granted) onStartRecording() else micPermissionLauncher.launch(permission)
                    },
                    enabled = !recording
                ) {
                    Icon(
                        imageVector = if (recording) Icons.Outlined.Mic else Icons.Outlined.MicNone,
                        contentDescription = if (recording) micStopCd else micStartCd,
                        tint = if (!recording) SeekerZeroColors.TextSecondary else SeekerZeroColors.TextDisabled
                    )
                }
                Box {
                    IconButton(
                        onClick = { cameraMenuOpen = true },
                        enabled = !recording
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CameraAlt,
                            contentDescription = stringResource(R.string.chat_camera),
                            tint = if (!recording) SeekerZeroColors.TextSecondary else SeekerZeroColors.TextDisabled
                        )
                    }
                    DropdownMenu(
                        expanded = cameraMenuOpen,
                        onDismissRequest = { cameraMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_camera_photo)) },
                            onClick = {
                                cameraMenuOpen = false
                                val cam = Manifest.permission.CAMERA
                                val granted = ContextCompat.checkSelfPermission(context, cam) ==
                                    PackageManager.PERMISSION_GRANTED
                                if (granted) photoLauncher.launch(onPrepareCamera(false))
                                else {
                                    pendingCameraMode = CameraMode.PHOTO
                                    cameraPermissionLauncher.launch(cam)
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_camera_video)) },
                            onClick = {
                                cameraMenuOpen = false
                                val cam = Manifest.permission.CAMERA
                                val granted = ContextCompat.checkSelfPermission(context, cam) ==
                                    PackageManager.PERMISSION_GRANTED
                                if (granted) videoLauncher.launch(onPrepareCamera(true))
                                else {
                                    pendingCameraMode = CameraMode.VIDEO
                                    cameraPermissionLauncher.launch(cam)
                                }
                            }
                        )
                    }
                }
                IconButton(
                    onClick = { filePicker.launch("*/*") },
                    enabled = !recording
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AttachFile,
                        contentDescription = attachCd,
                        tint = if (!recording) SeekerZeroColors.TextSecondary else SeekerZeroColors.TextDisabled
                    )
                }
                Box(modifier = Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 10.dp)) {
                    if (text.isEmpty()) {
                        Text(
                            text = hint,
                            color = SeekerZeroColors.TextDisabled,
                            fontSize = 14.sp
                        )
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        enabled = !recording,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 20.dp, max = 120.dp),
                        textStyle = TextStyle(
                            color = SeekerZeroColors.TextPrimary,
                            fontSize = 14.sp
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(SeekerZeroColors.Primary)
                    )
                }
                IconButton(
                    onClick = {
                        val toSend = text.trim()
                        onSend(toSend)
                        text = ""
                    },
                    enabled = canSend
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Send,
                        contentDescription = sendCd,
                        tint = if (canSend) SeekerZeroColors.Primary else SeekerZeroColors.TextDisabled
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentStrip(
    attachments: List<PendingAttachment>,
    onRemove: (String) -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(attachments, key = { it.id }) { a ->
            AttachmentChip(attachment = a, onRemove = { onRemove(a.id) })
        }
    }
}

@Composable
private fun AttachmentChip(
    attachment: PendingAttachment,
    onRemove: () -> Unit
) {
    val isImageLike = attachment.mime.startsWith("image/") ||
        attachment.mime.startsWith("video/")
    if (isImageLike && attachment.localSource != null) {
        ThumbnailAttachmentChip(attachment, onRemove)
    } else {
        TextAttachmentChip(attachment, onRemove)
    }
}

@Composable
private fun ThumbnailAttachmentChip(
    attachment: PendingAttachment,
    onRemove: () -> Unit
) {
    val isFailed = attachment.status == PendingAttachment.Status.FAILED
    val isUploading = attachment.status == PendingAttachment.Status.UPLOADING
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SeekerZeroColors.SurfaceVariant)
    ) {
        AsyncImage(
            model = attachment.localSource,
            contentDescription = attachment.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (isUploading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(SeekerZeroColors.Surface.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = SeekerZeroColors.Primary
                )
            }
        } else if (isFailed) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(SeekerZeroColors.Error.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                Text("!", color = SeekerZeroColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
        // Small close badge in the top-right.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(20.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(SeekerZeroColors.Surface.copy(alpha = 0.7f))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "remove",
                tint = SeekerZeroColors.TextPrimary,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

@Composable
private fun TextAttachmentChip(
    attachment: PendingAttachment,
    onRemove: () -> Unit
) {
    val isFailed = attachment.status == PendingAttachment.Status.FAILED
    val isUploading = attachment.status == PendingAttachment.Status.UPLOADING
    val background = when {
        isFailed -> SeekerZeroColors.Error.copy(alpha = 0.18f)
        else -> SeekerZeroColors.SurfaceVariant
    }
    val title = attachment.displayName
    val subtitle = when {
        isFailed -> attachment.errorMessage ?: "upload failed"
        isUploading -> formatProgress(attachment)
        else -> formatSize(attachment.sizeBytes)
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isUploading) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = SeekerZeroColors.Primary
            )
            Spacer(Modifier.width(8.dp))
        }
        Column {
            Text(
                text = title,
                color = SeekerZeroColors.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                color = if (isFailed) SeekerZeroColors.Error else SeekerZeroColors.TextSecondary,
                fontSize = 10.sp
            )
        }
        Spacer(Modifier.width(6.dp))
        IconButton(onClick = onRemove, modifier = Modifier.size(22.dp)) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "remove",
                tint = SeekerZeroColors.TextSecondary,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun RecordingPill(
    onStop: () -> Unit,
    onCancel: () -> Unit
) {
    var elapsedMs by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = System.currentTimeMillis()
        while (true) {
            elapsedMs = System.currentTimeMillis() - start
            delay(200)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SeekerZeroColors.Error.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Mic,
            contentDescription = null,
            tint = SeekerZeroColors.Error,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Recording · ${formatElapsed(elapsedMs)}",
            color = SeekerZeroColors.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "cancel",
                tint = SeekerZeroColors.TextSecondary,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onStop, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Send,
                contentDescription = "stop and attach",
                tint = SeekerZeroColors.Primary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

private fun formatProgress(a: PendingAttachment): String {
    if (a.sizeBytes <= 0L) return "uploading…"
    val pct = (a.progressFraction * 100).toInt()
    return "uploading ${pct}% · ${formatSize(a.sizeBytes)}"
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0L) return "—"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.2f GB".format(gb)
}

private fun formatElapsed(ms: Long): String {
    val s = ms / 1000
    val mm = s / 60
    val ss = s % 60
    return "%d:%02d".format(mm, ss)
}
