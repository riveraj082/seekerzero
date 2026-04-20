package dev.seekerzero.app.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Approval(
    val id: String,
    @SerialName("created_at_ms") val createdAtMs: Long,
    val category: String,
    val risk: String,
    val summary: String,
    val detail: String,
    val source: String,
    @SerialName("task_id") val taskId: String? = null
)

@Serializable
data class ApprovalsPendingResponse(
    val approvals: List<Approval> = emptyList(),
    @SerialName("server_time_ms") val serverTimeMs: Long
)

@Serializable
data class ApprovalsStreamResponse(
    val approvals: List<Approval> = emptyList(),
    @SerialName("server_time_ms") val serverTimeMs: Long,
    @SerialName("next_since_ms") val nextSinceMs: Long
)

@Serializable
data class ApprovalActionResponse(
    val ok: Boolean,
    val id: String,
    val resolution: String,
    @SerialName("resolved_at_ms") val resolvedAtMs: Long
)
