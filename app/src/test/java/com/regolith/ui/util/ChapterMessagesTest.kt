package com.regolith.ui.util

import com.regolith.domain.playback.ChapterWriteOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

/** [ChapterMessages]: one sentence per outcome, and the quick name sheet's says what it saved first. */
class ChapterMessagesTest {
    @Test fun `a quick name says what, when and where`() {
        assertEquals("“Car chase” at 24:13 · saved to the share", ChapterMessages.named("Car chase", 1_453_000, ChapterWriteOutcome.WRITTEN))
    }

    @Test fun `an unnamed mark is a mark`() {
        assertEquals("Mark at 0:45 · saved on this phone", ChapterMessages.named(null, 45_000, ChapterWriteOutcome.PHONE_ONLY))
    }

    @Test fun `the editor's line is unchanged`() {
        assertEquals("Saved on this phone — the share is read-only", ChapterMessages.saved(ChapterWriteOutcome.READ_ONLY))
    }
}
