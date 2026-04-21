package dev.seekerzero.app.ui.status

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.seekerzero.app.BuildConfig
import dev.seekerzero.app.api.models.ErroredTask
import dev.seekerzero.app.api.models.SubordinateStatus
import dev.seekerzero.app.ui.components.CardSurface
import dev.seekerzero.app.ui.components.SeekerZeroScaffold
import dev.seekerzero.app.util.ConnectionState
import dev.seekerzero.app.ui.theme.SeekerZeroColors

@Composable
fun StatusScreen(viewModel: StatusViewModel = viewModel()) {
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val lastContact by viewModel.lastContactAtMs.collectAsStateWithLifecycle()
    val reconnects by viewModel.reconnectCount.collectAsStateWithLifecycle()
    val health by viewModel.health.collectAsStateWithLifecycle()

    SeekerZeroScaffold(title = "Status") { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { ConnectionCard(connection, lastContact, reconnects) }
            item { A0VersionCard(a0Version = health?.a0Version ?: "—") }
            item { SubordinatesCard(health?.subordinates ?: emptyList()) }
            if ((health?.erroredTasks?.size ?: 0) > 0) {
                item { ErrorsCard(health?.erroredTasks ?: emptyList()) }
            }
            item { AppVersionCard() }
        }
    }
}

@Composable
private fun ConnectionCard(
    state: ConnectionState,
    lastContactMs: Long,
    reconnects: Int
) {
    CardSurface(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Pulse(color = stateColor(state), pulsing = state == ConnectionState.CONNECTED || state == ConnectionState.RECONNECTING)
            Spacer(Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stateLabel(state),
                    color = SeekerZeroColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (lastContactMs > 0) "Last contact ${formatRelative(lastContactMs)}" else "No contact yet",
                    color = SeekerZeroColors.TextSecondary,
                    fontSize = 12.sp
                )
                Text(
                    text = "Reconnects this session: $reconnects",
                    color = SeekerZeroColors.TextDisabled,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun A0VersionCard(a0Version: String) {
    CardSurface(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Agent Zero", color = SeekerZeroColors.TextSecondary, fontSize = 11.sp)
                Text(a0Version, color = SeekerZeroColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SubordinatesCard(subordinates: List<SubordinateStatus>) {
    CardSurface(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Subordinates", color = SeekerZeroColors.TextSecondary, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            if (subordinates.isEmpty()) {
                Text("—", color = SeekerZeroColors.TextDisabled, fontSize = 12.sp)
            } else {
                subordinates.forEach { sub ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (sub.status == "up") SeekerZeroColors.Success
                                    else SeekerZeroColors.Error
                                )
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(sub.name, color = SeekerZeroColors.TextPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "${sub.lastResponseMs}ms",
                            color = SeekerZeroColors.TextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorsCard(errored: List<ErroredTask>) {
    CardSurface(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                "Recent A0 errors",
                color = SeekerZeroColors.Error,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(6.dp))
            errored.forEach { e ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(
                        text = e.name,
                        color = SeekerZeroColors.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${e.state}  ·  retry ${e.retryCount}  ·  ${if (e.lastErrorAtMs > 0) formatRelative(e.lastErrorAtMs) else "—"}",
                        color = SeekerZeroColors.TextSecondary,
                        fontSize = 11.sp
                    )
                    if (e.lastErrorPreview.isNotBlank()) {
                        Text(
                            text = e.lastErrorPreview,
                            color = SeekerZeroColors.TextDisabled,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppVersionCard() {
    CardSurface(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Phone app", color = SeekerZeroColors.TextSecondary, fontSize = 11.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                text = "SeekerZero · ${BuildConfig.GIT_SHA} · ${BuildConfig.BUILD_DATE}",
                color = SeekerZeroColors.TextPrimary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun Pulse(color: androidx.compose.ui.graphics.Color, pulsing: Boolean) {
    val scale = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val s by transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.25f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseScale"
        )
        s
    } else {
        1f
    }
    Box(
        modifier = Modifier.size(18.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(color)
        )
    }
}

private fun stateColor(state: ConnectionState) = when (state) {
    ConnectionState.CONNECTED -> SeekerZeroColors.Success
    ConnectionState.RECONNECTING -> SeekerZeroColors.Warning
    ConnectionState.PAUSED_NO_NETWORK -> SeekerZeroColors.Warning
    ConnectionState.OFFLINE -> SeekerZeroColors.Error
    ConnectionState.DISCONNECTED -> SeekerZeroColors.TextDisabled
}

private fun stateLabel(state: ConnectionState) = when (state) {
    ConnectionState.CONNECTED -> "Connected"
    ConnectionState.RECONNECTING -> "Reconnecting\u2026"
    ConnectionState.PAUSED_NO_NETWORK -> "Paused — no network"
    ConnectionState.OFFLINE -> "Offline"
    ConnectionState.DISCONNECTED -> "Disconnected"
}

private fun formatRelative(ms: Long): String {
    if (ms <= 0) return "—"
    val now = System.currentTimeMillis()
    val diff = now - ms
    val secs = diff / 1000
    val mins = secs / 60
    val hours = mins / 60
    return when {
        secs < 5 -> "just now"
        secs < 60 -> "${secs}s ago"
        mins < 60 -> "${mins}m ago"
        hours < 24 -> "${hours}h ago"
        else -> "${hours / 24}d ago"
    }
}

