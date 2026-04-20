package dev.seekerzero.app.config

import android.net.Uri
import android.util.Base64
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

sealed class QrParseException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class EmptyPayload : QrParseException("QR payload was empty.")
    class BadUri(message: String) : QrParseException(message)
    class Base64Failure(cause: Throwable) : QrParseException("Could not decode QR payload (base64).", cause)
    class JsonFailure(cause: Throwable) : QrParseException("QR payload is not valid JSON.", cause)
    class UnsupportedVersion(val found: Int) : QrParseException("Unsupported QR payload version: $found (expected 1).")
    class MissingField(fieldName: String) : QrParseException("QR payload missing required field: $fieldName")
}

object QrParser {
    private const val SCHEME = "seekerzero"
    private const val HOST = "config"
    private const val PARAM = "payload"
    private const val BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(rawPayload: String): Result<QrConfigPayload> {
        val trimmed = rawPayload.trim()
        if (trimmed.isEmpty()) return Result.failure(QrParseException.EmptyPayload())

        val base64 = if (trimmed.startsWith("$SCHEME://")) {
            extractFromUri(trimmed) ?: return Result.failure(
                QrParseException.BadUri("QR URI missing '$PARAM' query parameter.")
            )
        } else {
            trimmed
        }

        val jsonBytes = try {
            Base64.decode(base64, BASE64_FLAGS)
        } catch (t: IllegalArgumentException) {
            return Result.failure(QrParseException.Base64Failure(t))
        }

        val payload = try {
            json.decodeFromString(QrConfigPayload.serializer(), String(jsonBytes, Charsets.UTF_8))
        } catch (t: SerializationException) {
            return Result.failure(QrParseException.JsonFailure(t))
        }

        if (payload.v != 1) return Result.failure(QrParseException.UnsupportedVersion(payload.v))
        if (payload.a0Host.isBlank()) return Result.failure(QrParseException.MissingField("a0_host"))
        if (payload.port !in 1..65535) return Result.failure(QrParseException.MissingField("port"))
        if (payload.clientId.isBlank()) return Result.failure(QrParseException.MissingField("client_id"))
        if (payload.mobileApiBase.isBlank()) return Result.failure(QrParseException.MissingField("mobile_api_base"))
        if (payload.displayName.isBlank()) return Result.failure(QrParseException.MissingField("display_name"))

        return Result.success(payload)
    }

    private fun extractFromUri(raw: String): String? {
        val uri = try {
            Uri.parse(raw)
        } catch (_: Exception) {
            return null
        }
        if (uri.host != HOST) return null
        return uri.getQueryParameter(PARAM)
    }
}
