package com.regolith.domain.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens to a playing film once Regolith cannot be seen at all
 * ([BackgroundPlay]): the screen going off keeps it only when the setting
 * says so, and every other way out pauses it.
 */
class BackgroundPlayTest {

    @Test
    fun `the screen off keeps the film when the setting is on`() {
        assertTrue(BackgroundPlay.keepsPlaying(screenOn = false, playWithScreenOff = true))
    }

    @Test
    fun `the screen off pauses it when the setting is off, as it ships`() {
        assertFalse(BackgroundPlay.keepsPlaying(screenOn = false, playWithScreenOff = false))
    }

    @Test
    fun `leaving the app with the screen on pauses it, whatever the setting says`() {
        // Picture-in-picture off, or its window closed: the setting is about
        // the screen locking, not about a film playing to nobody.
        assertFalse(BackgroundPlay.keepsPlaying(screenOn = true, playWithScreenOff = true))
        assertFalse(BackgroundPlay.keepsPlaying(screenOn = true, playWithScreenOff = false))
    }
}
