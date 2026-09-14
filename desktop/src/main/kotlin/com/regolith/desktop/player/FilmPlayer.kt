package com.regolith.desktop.player

import com.regolith.domain.smb.SeekableByteSource

/**
 * What the editor needs from a player, and nothing about VLC.
 *
 * [VlcPlayer] is the real one. The seam exists so the editor's state holder
 * can be unit-tested with a fake, and so a Mac without a usable libvlc can
 * still edit chapters by typed time. Properties are Compose state in the real
 * player, so a screen that reads them recomposes as the film plays.
 */
interface FilmPlayer {
    val positionMs: Long
    /** 0 until the film's length is known. */
    val durationMs: Long
    val playing: Boolean
    /** A sentence for the screen when the film cannot be played, else null. */
    val error: String?

    /** Play [src], replacing whatever was open. The player closes [src]. */
    fun open(src: SeekableByteSource)
    fun togglePause()
    fun seekTo(ms: Long)
    /** Stop and close the file; the player can open another. */
    fun release()
}
