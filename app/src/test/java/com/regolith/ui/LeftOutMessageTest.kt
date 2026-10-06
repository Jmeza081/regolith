package com.regolith.ui

import com.regolith.ui.util.FileOpMessages
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What Move and Delete say when they refuse because something was taken
 * back out of a picked folder: what would happen, and what to do instead.
 */
class LeftOutMessageTest {

    @Test
    fun `one folder is named, with the way round it`() {
        assertEquals(
            "You un-picked something inside “Films”, so Delete would take it too. Open “Films” and pick what to delete.",
            FileOpMessages.forLeftOut("delete", listOf("Films")),
        )
    }

    @Test
    fun `several folders are spoken of together`() {
        assertEquals(
            "You un-picked something inside folders you picked, so Move would take it too. Open them and pick what to move.",
            FileOpMessages.forLeftOut("move", listOf("Films", "Series")),
        )
    }
}
