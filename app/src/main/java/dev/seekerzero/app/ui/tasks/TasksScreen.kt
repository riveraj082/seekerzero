package dev.seekerzero.app.ui.tasks

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.seekerzero.app.R
import dev.seekerzero.app.api.models.TaskDto
import dev.seekerzero.app.ui.components.CardSurface
import dev.seekerzero.app.ui.components.SeekerZeroScaffold
import dev.seekerzero.app.ui.theme.SeekerZeroColors
import dev.seekerzero.app.ui.theme.seekerZeroSwitchColors

@Composable
fun TasksScreen(
    onCompose: () -> Unit,
    viewModel: TasksViewModel = viewModel()
) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    // Refresh on every resume — specifically, when the user pops back from
    // TaskComposerScreen after creating a task. The Composer and TasksScreen
    // each get their own VM (different nav-entry ViewModelStore), so the
    // create() in the composer's VM doesn't touch this VM's list state.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SeekerZeroScaffold(
        title = stringResource(R.string.tab_tasks),
        actions = {
            IconButton(onClick = onCompose) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = "New task",
                    tint = SeekerZeroColors.Primary
                )
            }
        }
    ) { pad ->
        Box(modifier = Modifier.fillMaxSize().padding(pad)) {
            when {
                loading && tasks.isEmpty() -> CenteredText("Loading tasks\u2026")
                error != null && tasks.isEmpty() -> CenteredText("Error: ${error}")
                tasks.isEmpty() -> CenteredText("No scheduled tasks yet. Tap + to add one.")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(tasks, key = { it.uuid }) { task ->
                        TaskCard(
                            task = task,
                            busy = busy.contains(task.uuid),
                            onToggle = { viewModel.toggle(task) },
                            onRun = { viewModel.runNow(task.uuid) },
                            onDelete = { viewModel.delete(task.uuid) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredText(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = SeekerZeroColors.TextSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun TaskCard(
    task: TaskDto,
    busy: Boolean,
    onToggle: () -> Unit,
    onRun: () -> Unit,
    onDelete: () -> Unit
) {
    CardSurface(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    Column {
                        Text(
                            text = task.name,
                            color = SeekerZeroColors.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "cron: ${task.schedule?.cronLine ?: "—"}",
                            color = SeekerZeroColors.TextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                StatePill(state = task.state)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Last: ${formatRelative(task.lastRunMs)}   Next: ${formatRelative(task.nextRunMs)}",
                    color = SeekerZeroColors.TextSecondary,
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = task.state != "disabled",
                    enabled = !busy,
                    onCheckedChange = { onToggle() },
                    colors = seekerZeroSwitchColors()
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = if (task.state == "disabled") "Disabled" else "Enabled",
                    color = SeekerZeroColors.TextSecondary,
                    fontSize = 12.sp
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRun, enabled = !busy) {
                    Icon(
                        imageVector = Icons.Outlined.PlayArrow,
                        contentDescription = "Run now",
                        tint = if (busy) SeekerZeroColors.TextDisabled else SeekerZeroColors.Primary
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "Delete task",
                        tint = SeekerZeroColors.Error
                    )
                }
            }
            val preview = task.lastResultPreview.trim()
            if (preview.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = preview,
                    color = SeekerZeroColors.TextDisabled,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 3
                )
            }
        }
    }
}

@Composable
private fun StatePill(state: String) {
    val (bg, fg) = when (state) {
        "running" -> SeekerZeroColors.Primary to SeekerZeroColors.OnPrimary
        "disabled" -> SeekerZeroColors.SurfaceVariant to SeekerZeroColors.TextDisabled
        "error" -> SeekerZeroColors.Error to SeekerZeroColors.OnPrimary
        else -> SeekerZeroColors.SurfaceVariant to SeekerZeroColors.TextSecondary
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text = state, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

private fun formatRelative(ms: Long): String {
    if (ms <= 0) return "—"
    val now = System.currentTimeMillis()
    val diff = ms - now
    val absDiff = kotlin.math.abs(diff)
    val mins = absDiff / 60_000
    val hours = mins / 60
    val days = hours / 24
    val body = when {
        mins < 1 -> "now"
        mins < 60 -> "${mins}m"
        hours < 24 -> "${hours}h"
        days < 30 -> "${days}d"
        else -> "${days / 30}mo"
    }
    return if (diff < 0) "$body ago" else "in $body"
}
