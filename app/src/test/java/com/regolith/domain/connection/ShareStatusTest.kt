package com.regolith.domain.connection

import org.junit.Assert.assertEquals
import org.junit.Test

/** [nextLink]: one missed answer never turns the dot grey. */
class ShareStatusTest {
    @Test fun `an answer is connected`() = assertEquals(ShareLink.CONNECTED, nextLink(firstOk = true, recheckOk = null))
    @Test fun `a miss that the recheck covers stays connected`() = assertEquals(ShareLink.CONNECTED, nextLink(firstOk = false, recheckOk = true))
    @Test fun `two misses in a row are out of reach`() = assertEquals(ShareLink.OUT_OF_REACH, nextLink(firstOk = false, recheckOk = false))
}
