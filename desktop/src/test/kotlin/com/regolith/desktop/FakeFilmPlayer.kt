package com.regolith.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.regolith.desktop.player.FilmPlayer
import com.regolith.domain.smb.SeekableByteSource

/**
 * A player that plays nothing. Tests set [positionMs]/[durationMs] and read
 * what the editor asked for. Compose state, like the real one, so a screen
 * under test recomposes when a test changes it.
 */
class FakeFilmPlayer : FilmPlayer {
    override var positionMs by mutableStateOf(0L)
    override var durationMs by mutableStateOf(0L)
    override var playing by mutableStateOf(false)
    override var error by mutableStateOf<String?>(null)
    var opened: SeekableByteSource? = null
    val seeks = mutableListOf<Long>()

    override fun open(src: SeekableByteSource) { opened = src }
    override fun togglePause() { playing = !playing }
    override fun seekTo(ms: Long) { seeks += ms; positionMs = ms }
    override fun release() { opened?.close(); opened = null }
}
