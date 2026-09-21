package com.regolith.domain.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a scan does when a listing fails.
 *
 * A walk over a share is thousands of listings in a row, so the cost of
 * getting this wrong compounds: retry nothing and a dropped VPN tunnel ends
 * the scan, retry everything and a share full of ACL'd folders takes twice
 * as long to tell you the same thing.
 */
class ListingRetryTest {
    @Test
    fun `a dropped connection is worth one more go`() {
        assertEquals(LISTING_RETRY_PAUSE_MS, listingRetryDelayMs(1, SmbFailure.Unreachable("tower")))
    }

    @Test
    fun `but only one`() {
        assertNull(
            "a listing that failed twice has already spent two response timeouts",
            listingRetryDelayMs(2, SmbFailure.Unreachable("tower")),
        )
    }

    @Test
    fun `a refused login is refused the same way twice`() {
        assertNull(listingRetryDelayMs(1, SmbFailure.AuthFailed()))
    }

    @Test
    fun `a path that is not there will not have appeared`() {
        assertNull(listingRetryDelayMs(1, SmbFailure.NotFound("Films/gone")))
    }

    @Test
    fun `a permission denial is the server's settled opinion`() {
        assertNull(listingRetryDelayMs(1, SmbFailure.Forbidden("Films/private")))
    }

    @Test
    fun `the pause is long enough to matter and short enough not to be felt`() {
        assert(LISTING_RETRY_PAUSE_MS in 1_000..5_000) { "pause was $LISTING_RETRY_PAUSE_MS" }
    }
}
