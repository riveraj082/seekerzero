package dev.seekerzero.app.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class PushItem(
    val id: Long,
    @SerialName("created_at_ms") val createdAtMs: Long,
    val title: String,
    val body: String,
    @SerialName("deep_link") val deepLink: String? = null,
    val payload: JsonObject? = null,
)

@Serializable
data class PushPendingResponse(
    val ok: Boolean,
    val items: List<PushItem> = emptyList(),
)

@Serializable
data class PushAckRequest(val ids: List<Long>)

@Serializable
data class PushAckResponse(val ok: Boolean, val acked: Int = 0)
