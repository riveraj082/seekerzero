package dev.seekerzero.app.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SubordinateStatus(
    val name: String,
    val status: String,
    @SerialName("last_response_ms") val lastResponseMs: Long
)

@Serializable
data class ErroredTask(
    val uuid: String,
    val name: String,
    val state: String,
    @SerialName("retry_count") val retryCount: Int = 0,
    @SerialName("last_error_at_ms") val lastErrorAtMs: Long = 0,
    @SerialName("last_error_preview") val lastErrorPreview: String = ""
)

@Serializable
data class HealthResponse(
    val ok: Boolean,
    @SerialName("server_time_ms") val serverTimeMs: Long,
    @SerialName("a0_version") val a0Version: String,
    val subordinates: List<SubordinateStatus> = emptyList(),
    @SerialName("errored_tasks") val erroredTasks: List<ErroredTask> = emptyList()
)
