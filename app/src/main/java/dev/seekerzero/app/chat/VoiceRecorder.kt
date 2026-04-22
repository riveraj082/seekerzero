package dev.seekerzero.app.chat

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import dev.seekerzero.app.util.LogCollector
import java.io.File

/**
 * Thin wrapper around MediaRecorder for on-device voice capture.
 * Records AAC-in-MP4 (.m4a), 44.1 kHz mono, 64 kbps — small files that
 * A0's voice-to-text path can consume directly.
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtMs: Long = 0L

    val isRecording: Boolean get() = recorder != null

    /** Returns the destination file on success, or null if start fails. */
    fun start(): File? {
        if (recorder != null) return outputFile
        val dir = File(context.cacheDir, "voice").apply { mkdirs() }
        val file = File(dir, "voice_${System.currentTimeMillis()}.m4a")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        return try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(44_100)
            rec.setAudioChannels(1)
            rec.setAudioEncodingBitRate(64_000)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            outputFile = file
            startedAtMs = System.currentTimeMillis()
            file
        } catch (t: Throwable) {
            LogCollector.w("VoiceRecorder", "start() failed: ${t.message}")
            runCatching { rec.reset() }
            runCatching { rec.release() }
            runCatching { file.delete() }
            recorder = null
            outputFile = null
            null
        }
    }

    /** Stop and return (file, durationMs), or null if nothing was recorded. */
    fun stop(): Pair<File, Long>? {
        val rec = recorder ?: return null
        val file = outputFile
        val duration = System.currentTimeMillis() - startedAtMs
        recorder = null
        outputFile = null
        startedAtMs = 0L
        return try {
            runCatching { rec.stop() }
            runCatching { rec.reset() }
            rec.release()
            if (file == null || !file.exists() || file.length() == 0L) null
            else file to duration
        } catch (t: Throwable) {
            LogCollector.w("VoiceRecorder", "stop() failed: ${t.message}")
            runCatching { rec.release() }
            null
        }
    }

    fun cancel() {
        val rec = recorder ?: return
        val file = outputFile
        recorder = null
        outputFile = null
        startedAtMs = 0L
        runCatching { rec.stop() }
        runCatching { rec.reset() }
        runCatching { rec.release() }
        runCatching { file?.delete() }
    }

    fun elapsedMs(): Long = if (startedAtMs == 0L) 0L else System.currentTimeMillis() - startedAtMs
}
