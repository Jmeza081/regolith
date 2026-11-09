package com.regolith.ui.library

import com.regolith.domain.library.MixOutcome.DONE
import com.regolith.domain.library.MixOutcome.FAILED
import com.regolith.domain.library.MixOutcome.READ_ONLY
import com.regolith.domain.library.MixOutcome.UNREACHABLE
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the message line says after Mark as mix, from the menu or the selection. */
class MixMessageTest {
    @Test fun `one folder, done`() {
        assertEquals("Marked as a mix" to false, mixMessage(true, 1, listOf(DONE)))
        assertEquals("Marked as a collection" to false, mixMessage(false, 1, listOf(DONE)))
    }

    @Test fun `several folders, all done`() {
        assertEquals("Marked 3 folders as mixes" to false, mixMessage(true, 3, listOf(DONE, DONE, DONE)))
        assertEquals("Marked 2 folders as collections" to false, mixMessage(false, 2, listOf(DONE, DONE)))
    }

    @Test fun `one folder that could not be marked says why`() {
        assertEquals("Couldn’t mark this folder: the share is read-only" to true, mixMessage(true, 1, listOf(READ_ONLY)))
        assertEquals("Couldn’t mark this folder: try again" to true, mixMessage(true, 1, listOf(FAILED)))
    }

    @Test fun `a run cut short by the server counts what got done`() {
        assertEquals("Marked 1 of 4 folders: couldn’t reach the server" to true, mixMessage(true, 4, listOf(DONE, UNREACHABLE)))
    }
}
