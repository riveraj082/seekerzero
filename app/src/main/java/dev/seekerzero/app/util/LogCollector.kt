package dev.seekerzero.app.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue

enum class LogLevel { D, I, W, E }

data class LogLine(
    val timestampMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String
)

object LogCollector {
    private const val BUFFER_SIZE = 500
    private const val MAX_FILE_BYTES = 5L * 1024 * 1024
    private const val LOG_DIR = "logs"
    private const val LOG_NAME = "seekerzero.log"
    private const val LOG_BACKUP = "seekerzero.log.1"

    private val buffer = ArrayBlockingQueue<LogLine>(BUFFER_SIZE)
    private val tsFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val writeLock = Any()

    @Volatile private var logDir: File? = null

    fun init(context: Context) {
        val dir = File(context.filesDir, LOG_DIR)
        if (!dir.exists()) dir.mkdirs()
        logDir = dir
    }

    fun d(tag: String, msg: String) = record(LogLevel.D, tag, msg, null)
    fun i(tag: String, msg: String) = record(LogLevel.I, tag, msg, null)
    fun w(tag: String, msg: String) = record(LogLevel.W, tag, msg, null)
    fun e(tag: String, msg: String, t: Throwable? = null) = record(LogLevel.E, tag, msg, t)

    fun recent(): List<LogLine> = buffer.toList()

    private fun record(level: LogLevel, tag: String, msg: String, t: Throwable?) {
        val now = System.currentTimeMillis()
        val composed = if (t == null) msg else "$msg\n${stack(t)}"
        val line = LogLine(now, level, tag, composed)
        if (!buffer.offer(line)) {
            buffer.poll()
            buffer.offer(line)
        }
        when (level) {
            LogLevel.D -> Log.d(tag, composed)
            LogLevel.I -> Log.i(tag, composed)
            LogLevel.W -> Log.w(tag, composed)
            LogLevel.E -> Log.e(tag, composed)
        }
        appendToFile(line)
    }

    private fun appendToFile(line: LogLine) {
        val dir = logDir ?: return
        synchronized(writeLock) {
            val file = File(dir, LOG_NAME)
            if (file.exists() && file.length() >= MAX_FILE_BYTES) {
                val backup = File(dir, LOG_BACKUP)
                if (backup.exists()) backup.delete()
                file.renameTo(backup)
            }
            try {
                PrintWriter(FileWriter(File(dir, LOG_NAME), true)).use { w ->
                    w.println("${tsFormat.format(Date(line.timestampMs))} ${line.level} ${line.tag}: ${line.message}")
                }
            } catch (_: Exception) {
                // Best-effort; never throw from a logger.
            }
        }
    }

    private fun stack(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }
}
