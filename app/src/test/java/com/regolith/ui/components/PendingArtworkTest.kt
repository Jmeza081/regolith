package com.regolith.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The count a wall waits on before it fades back in beside a closing page:
 * pictures counted in while they load and out when they are drawn.
 */
class PendingArtworkTest {

    @Test
    fun `pictures are counted in while they load and out when they are drawn`() {
        val pending = PendingArtwork()
        pending.started()
        pending.started()
        assertEquals(2, pending.count)
        pending.finished()
        assertEquals(1, pending.count)
        pending.finished()
        assertEquals(0, pending.count)
    }

    @Test
    fun `an extra goodbye never takes the count below nothing`() {
        val pending = PendingArtwork()
        pending.finished()
        assertEquals(0, pending.count)
        pending.started()
        pending.finished()
        pending.finished()
        assertEquals(0, pending.count)
    }
}
