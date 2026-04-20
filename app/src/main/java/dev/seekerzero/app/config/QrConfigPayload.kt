package dev.seekerzero.app.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class QrConfigPayload(
    val v: Int,
    @SerialName("a0_host") val a0Host: String,
    @SerialName("port") val port: Int = 50080,
    @SerialName("mobile_api_base") val mobileApiBase: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("display_name") val displayName: String
)
