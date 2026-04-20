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
data class HealthResponse(
    val ok: Boolean,
    @SerialName("server_time_ms") val serverTimeMs: Long,
    @SerialName("a0_version") val a0Version: String,
    val subordinates: List<SubordinateStatus> = emptyList()
)
