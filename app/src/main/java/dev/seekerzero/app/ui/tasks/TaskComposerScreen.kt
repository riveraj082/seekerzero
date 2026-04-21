package dev.seekerzero.app.ui.tasks

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.seekerzero.app.api.models.TaskSchedule
import dev.seekerzero.app.ui.components.CardSurface
import dev.seekerzero.app.ui.components.SeekerZeroScaffold
import dev.seekerzero.app.ui.theme.SeekerZeroColors

@Composable
fun TaskComposerScreen(
    onBack: () -> Unit,
    viewModel: TasksViewModel = viewModel()
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var minute by remember { mutableStateOf("0") }
    var hour by remember { mutableStateOf("9") }
    var day by remember { mutableStateOf("*") }
    var month by remember { mutableStateOf("*") }
    var weekday by remember { mutableStateOf("*") }
    var submitting by remember { mutableStateOf(false) }

    SeekerZeroScaffold(title = "New scheduled task", onBack = onBack) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            LabeledField(label = "Name") {
                SimpleTextField(value = name, onValueChange = { name = it }, singleLine = true)
            }
            Spacer(Modifier.height(12.dp))
            LabeledField(label = "Prompt") {
                SimpleTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    singleLine = false,
                    minLines = 5
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Schedule (cron: minute hour day month weekday)",
                color = SeekerZeroColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CronCell("min", minute, { minute = it }, Modifier.weight(1f))
                CronCell("hour", hour, { hour = it }, Modifier.weight(1f))
                CronCell("day", day, { day = it }, Modifier.weight(1f))
                CronCell("month", month, { month = it }, Modifier.weight(1f))
                CronCell("wday", weekday, { weekday = it }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "`*` = any. Examples: every hour = `0 * * * *`, daily 09:00 = `0 9 * * *`, Mondays 10:00 = `0 10 * * 1`.",
                color = SeekerZeroColors.TextSecondary,
                fontSize = 11.sp
            )

            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                    Text("Cancel", color = SeekerZeroColors.TextSecondary)
                }
                Button(
                    onClick = {
                        if (submitting) return@Button
                        if (name.isBlank() || prompt.isBlank()) {
                            Toast.makeText(context, "Name and prompt required", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        submitting = true
                        viewModel.create(
                            name = name.trim(),
                            prompt = prompt.trim(),
                            schedule = TaskSchedule(
                                minute = minute.ifBlank { "*" },
                                hour = hour.ifBlank { "*" },
                                day = day.ifBlank { "*" },
                                month = month.ifBlank { "*" },
                                weekday = weekday.ifBlank { "*" }
                            )
                        ) { ok, err ->
                            submitting = false
                            if (ok) {
                                Toast.makeText(context, "Task created", Toast.LENGTH_SHORT).show()
                                onBack()
                            } else {
                                Toast.makeText(context, "Failed: $err", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = !submitting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SeekerZeroColors.Primary,
                        contentColor = SeekerZeroColors.OnPrimary
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (submitting) "Creating\u2026" else "Create")
                }
            }
        }
    }
}

@Composable
private fun LabeledField(label: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = label,
            color = SeekerZeroColors.TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun SimpleTextField(
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean,
    minLines: Int = 1
) {
    CardSurface(modifier = Modifier.fillMaxWidth()) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = minLines,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .heightIn(min = 20.dp),
            textStyle = TextStyle(
                color = SeekerZeroColors.TextPrimary,
                fontSize = 14.sp
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(SeekerZeroColors.Primary)
        )
    }
}

@Composable
private fun CronCell(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            color = SeekerZeroColors.TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(2.dp))
        CardSurface(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(
                        color = SeekerZeroColors.TextPrimary,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(SeekerZeroColors.Primary)
                )
            }
        }
    }
}
