package com.regolith.ui.poster

import com.regolith.domain.artwork.WholePoster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The line over Set as poster's choice, and when Set as poster can be pressed. */
class SetPosterUiStateTest {

    private val onShare = SetPosterUiState(folderName = "Lake house 2024", pictureTitle = "IMG_4821", pictureName = "IMG_4821.jpg", loading = false)

    @Test
    fun `the subtitle names the folder and the picture`() {
        assertEquals("Lake house 2024 · from IMG_4821", onShare.subtitle)
        assertEquals("Lake house 2024 · from your phone", SetPosterUiState(folderName = "Lake house 2024").subtitle)
    }

    @Test
    fun `the crop says how to frame it`() {
        assertEquals("Pinch and drag the picture to frame it", onShare.hint)
        assertEquals("Pinch and drag the picture to frame it. A cropped GIF is a still poster", onShare.copy(moves = true).hint)
    }

    @Test
    fun `the whole picture says what happens to it`() {
        assertEquals("IMG_4821.jpg is renamed poster.jpg, as it is", onShare.copy(whole = true, wholeHow = WholePoster.RENAME).hint)
        val heic = onShare.copy(pictureName = "IMG_4821.HEIC", whole = true, wholeHow = WholePoster.COPY)
        assertEquals("Saved as poster.jpg, a copy sized for a poster; IMG_4821.HEIC stays", heic.hint)
        assertEquals("Saved as poster.gif: it moves on the Library", SetPosterUiState(whole = true, moves = true).hint)
    }

    @Test
    fun `the poster itself used whole has nothing to do`() {
        val already = onShare.copy(whole = true, wholeHow = WholePoster.ALREADY, picture = null)
        assertEquals("This is Lake house 2024’s poster already", already.hint)
        assertTrue(already.alreadyPoster)
        assertFalse(already.canSave)
    }

    @Test
    fun `nothing can be pressed while the picture is coming, or on a source that cannot change`() {
        assertFalse(onShare.canSave)
        assertFalse(onShare.copy(readOnly = "Nothing can be changed while spoof mode is on").canSave)
    }
}
