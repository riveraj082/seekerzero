package dev.seekerzero.app.chat

import android.media.MediaPlayer
import dev.seekerzero.app.util.LogCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single shared MediaPlayer instance so only one audio attachment plays at a
 * time. Composables read `state` and call `play(id, url)` / `pauseOrResume(id)`.
 * Tapping a different tile while something is already playing preempts it.
 */
object AudioPlaybackController {

    data class State(
        val id: String? = null,
        val url: String? = null,
        val isPlaying: Boolean = false,
        val positionMs: Int = 0,
        val durationMs: Int = 0,
    )

    private var player: MediaPlayer? = null
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Synchronized
    fun play(id: String, url: String) {
        val current = _state.value
        // Same attachment: resume from paused position.
        if (current.id == id && player != null) {
            if (!current.isPlaying) {
                player?.start()
                _state.value = current.copy(isPlaying = true)
            }
            return
        }
        // New attachment: tear down and start fresh.
        release()
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setDataSource(url)
            mp.setOnPreparedListener {
                _state.value = State(
                    id = id,
                    url = url,
                    isPlaying = true,
                    positionMs = 0,
                    durationMs = it.duration.coerceAtLeast(0),
                )
                it.start()
            }
            mp.setOnCompletionListener {
                _state.value = _state.value.copy(
                    isPlaying = false,
                    positionMs = _state.value.durationMs,
                )
            }
            mp.setOnErrorListener { _, what, extra ->
                LogCollector.w("AudioPlayback", "MediaPlayer error what=$what extra=$extra")
                release()
                true
            }
            mp.prepareAsync()
            _state.value = State(id = id, url = url, isPlaying = false)
        } catch (t: Throwable) {
            LogCollector.w("AudioPlayback", "play() failed: ${t.message}")
            release()
        }
    }

    @Synchronized
    fun pauseOrResume(id: String) {
        val current = _state.value
        if (current.id != id) return
        val mp = player ?: return
        if (current.isPlaying) {
            runCatching { mp.pause() }
            _state.value = current.copy(isPlaying = false)
        } else {
            runCatching { mp.start() }
            _state.value = current.copy(isPlaying = true)
        }
    }

    @Synchronized
    fun tickPosition() {
        val current = _state.value
        val mp = player ?: return
        if (!current.isPlaying) return
        val pos = runCatching { mp.currentPosition }.getOrDefault(current.positionMs)
        _state.value = current.copy(positionMs = pos)
    }

    @Synchronized
    fun release() {
        player?.let { mp ->
            runCatching { mp.stop() }
            runCatching { mp.reset() }
            runCatching { mp.release() }
        }
        player = null
        _state.value = State()
    }
}
