package dev.seekerzero.app.chat

import android.net.Uri
import java.util.UUID

/**
 * A single attachment the user has queued to send with the next message.
 * Uploads run in the background; once `serverPath` is set the chip turns
 * from "uploading" to "ready".
 */
data class PendingAttachment(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val mime: String,
    val sizeBytes: Long,
    val status: Status = Status.UPLOADING,
    val bytesSent: Long = 0L,
    val serverPath: String? = null,
    val errorMessage: String? = null,
    // Set when the attachment originated from on-device voice or camera
    // capture. We clean up the local cache file once the upload finishes.
    val localCachePath: String? = null,
    // Source on-device URI (content:// from the picker, or file:// from the
    // camera/voice capture). Used for rendering a thumbnail in the composer
    // strip while the upload is in flight.
    val localSource: Uri? = null,
) {
    enum class Status { UPLOADING, READY, FAILED }

    val progressFraction: Float
        get() {
            if (sizeBytes <= 0L) return 0f
            return (bytesSent.toFloat() / sizeBytes.toFloat()).coerceIn(0f, 1f)
        }
}
