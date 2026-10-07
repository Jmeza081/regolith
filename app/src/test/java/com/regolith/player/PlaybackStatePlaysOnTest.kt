package com.regolith.player

import com.regolith.domain.playback.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [PlaybackState.playsOnTo]: what the player goes on to by itself when a film
 * ends — the one rule the player's Up next card and the mini player share.
 */
class PlaybackStatePlaysOnTest {

    private val next = NextItem(fileId = 42, name = "Free Solo", sizeBytes = 0, durationMs = 20_000)
    private val first = NextItem(fileId = 40, name = "Arrival", sizeBytes = 0, durationMs = 20_000)

    @Test
    fun `keep playing goes on to the next in the folder`() {
        assertEquals(next, PlaybackState(next = listOf(next)).playsOnTo(keepPlaying = true))
    }

    @Test
    fun `without keep playing a single film stops at its end`() {
        assertNull(PlaybackState(next = listOf(next)).playsOnTo(keepPlaying = false))
    }

    @Test
    fun `a running order plays on whatever the setting says`() {
        assertEquals(next, PlaybackState(next = listOf(next), queued = true).playsOnTo(keepPlaying = false))
    }

    @Test
    fun `repeating all comes back round to the first at the end`() {
        val last = PlaybackState(next = emptyList(), wrapTo = first, repeat = RepeatMode.ALL)
        assertEquals(first, last.playsOnTo(keepPlaying = false))
    }

    @Test
    fun `with nothing after it, a film stops even with keep playing on`() {
        assertNull(PlaybackState(next = emptyList(), wrapTo = first).playsOnTo(keepPlaying = true))
    }
}
