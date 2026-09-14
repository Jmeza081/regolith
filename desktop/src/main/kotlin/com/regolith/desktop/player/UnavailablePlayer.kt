package com.regolith.desktop.player

import com.regolith.domain.smb.SeekableByteSource

/**
 * The player on a Mac where libvlc could not be loaded. It plays nothing and
 * says why; the editor still opens, so chapters can be added and timed by
 * typing their start.
 */
class UnavailablePlayer(override val error: String = MESSAGE) : FilmPlayer {
    override val positionMs: Long = 0
    override val durationMs: Long = 0
    override val playing: Boolean = false

    override fun open(src: SeekableByteSource) = src.close()
    override fun togglePause() = Unit
    override fun seekTo(ms: Long) = Unit
    override fun release() = Unit

    companion object {
        const val MESSAGE = "Video is unavailable: VLC's libraries were not found. You can still add chapters and type their times."
    }
}
