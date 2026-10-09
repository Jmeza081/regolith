package com.regolith.ui

import com.regolith.ui.components.PosterMaking
import com.regolith.ui.components.PosterSwap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the sheet says before a poster is set over one already there (the
 * canvas's Poster-Confirm): the kept poster's new name, what the new one is
 * made of, and that nothing is lost.
 */
class PosterSwapTest {

    private fun swap(making: PosterMaking, kept: Map<String, String> = mapOf("poster.jpg" to "poster (8 Oct).jpg"), nextName: String = "poster.jpg") =
        PosterSwap.of(
            folderId = 12, folderName = "Lake house 2024", kept = kept, next = null,
            nextName = nextName, making = making, source = "IMG_4821",
        )

    @Test
    fun `a crop keeps the picture and the old poster, as the board says`() {
        val s = swap(PosterMaking.CROP)
        assertEquals("poster.jpg, your crop", s.nextNote)
        assertEquals(
            "Lake house 2024’s poster becomes your crop of IMG_4821, and the picture itself stays as it is. " +
                "The poster there now is kept, renamed, and stays under Images.",
            s.body,
        )
        assertEquals("poster (8 Oct).jpg", s.kept.single().keptName)
        assertNull(s.moreKept)
    }

    @Test
    fun `a whole picture renamed says what it is called now`() {
        val s = swap(PosterMaking.RENAMED, nextName = "poster.png")
        assertEquals("poster.png, IMG_4821 renamed", s.nextNote)
        assertEquals(
            "IMG_4821 becomes Lake house 2024’s poster, renamed poster.png. The poster there now is kept, renamed, and stays under Images.",
            s.body,
        )
    }

    @Test
    fun `a frame and a phone picture are named for what they are`() {
        assertEquals("poster.jpg, this frame", swap(PosterMaking.FRAME).nextNote)
        assertEquals("poster.jpg, your picture", swap(PosterMaking.PHONE_WHOLE).nextNote)
        assertEquals(
            "Lake house 2024’s poster becomes your crop of the picture from your phone. The poster there now is kept, renamed, and stays under Images.",
            swap(PosterMaking.PHONE_CROP).body,
        )
    }

    @Test
    fun `several pictures of its own are counted, and the first is the one shown`() {
        val s = swap(PosterMaking.COPY, kept = linkedMapOf("poster.jpg" to "poster (8 Oct).jpg", "folder.jpg" to "folder (8 Oct).jpg"))
        assertEquals("+ 1 more kept", s.moreKept)
        assertEquals("poster (8 Oct).jpg", s.kept.first().keptName)
        assertEquals(
            "Lake house 2024’s poster becomes a JPEG copy of IMG_4821, sized for a poster, and the picture itself stays as it is. " +
                "The 2 pictures acting as its poster now are kept, renamed, and stay under Images.",
            s.body,
        )
    }
}
