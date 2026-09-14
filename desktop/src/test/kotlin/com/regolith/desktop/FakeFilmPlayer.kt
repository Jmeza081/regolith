package com.regolith.desktop

import com.regolith.desktop.player.FilmPlayer
import com.regolith.domain.smb.SeekableByteSource

/** A player that plays nothing: tests set [positionMs]/[durationMs] and read what the editor asked for. */
class FakeFilmPlayer : FilmPlayer {
    override var positionMs = 0L
    override var durationMs = 0L
    override var playing = false
    override var error: String? = null
    var opened: SeekableByteSource? = null
    val seeks = mutableListOf<Long>()

    override fun open(src: SeekableByteSource) { opened = src }
    override fun togglePause() { playing = !playing }
    override fun seekTo(ms: Long) { seeks += ms; positionMs = ms }
    override fun release() { opened?.close(); opened = null }
}
