package dev.seekerzero.app.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TaskSchedule(
    val minute: String = "*",
    val hour: String = "*",
    val day: String = "*",
    val month: String = "*",
    val weekday: String = "*",
    val timezone: String = ""
) {
    /** Render this schedule as the standard 5-field cron string. */
    val cronLine: String get() = listOf(minute, hour, day, month, weekday).joinToString(" ")
}

@Serializable
data class TaskDto(
    val uuid: String,
    val name: String,
    val state: String,
    val type: String,
    val schedule: TaskSchedule? = null,
    @SerialName("last_run_ms") val lastRunMs: Long = 0,
    @SerialName("next_run_ms") val nextRunMs: Long = 0,
    @SerialName("last_result_preview") val lastResultPreview: String = "",
    @SerialName("last_result_truncated") val lastResultTruncated: Boolean = false,
    @SerialName("created_at_ms") val createdAtMs: Long = 0,
    @SerialName("updated_at_ms") val updatedAtMs: Long = 0
)

@Serializable
data class TasksListResponse(
    val tasks: List<TaskDto> = emptyList(),
    @SerialName("server_time_ms") val serverTimeMs: Long
)

@Serializable
data class TaskCreateRequest(
    val name: String,
    val prompt: String,
    @SerialName("system_prompt") val systemPrompt: String = "",
    val schedule: TaskSchedule
)

@Serializable
data class TaskCreateResponse(
    val ok: Boolean,
    val uuid: String,
    val task: TaskDto? = null
)

@Serializable
data class TaskActionResponse(
    val ok: Boolean,
    val uuid: String,
    val state: String? = null,
    @SerialName("started_at_ms") val startedAtMs: Long? = null
)
