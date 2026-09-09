package com.regolith.domain

import com.regolith.domain.transfer.StorageCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageCheckTest {
    private val gb = 1024L * 1024 * 1024

    @Test fun `room means the rest of the file plus the reserve`() {
        assertTrue(StorageCheck.hasRoom(freeBytes = 5 * gb, totalBytes = 4 * gb, bytesDone = 0))
        assertFalse(StorageCheck.hasRoom(freeBytes = 4 * gb, totalBytes = 4 * gb, bytesDone = 0))
        assertTrue(StorageCheck.hasRoom(freeBytes = 2 * gb, totalBytes = 4 * gb, bytesDone = 3 * gb)) // resuming needs only what is left
    }

    @Test fun `shortfall is what the design prints as needed`() {
        assertEquals(0, StorageCheck.shortfall(freeBytes = 5 * gb, totalBytes = 4 * gb, bytesDone = 0))
        assertEquals(StorageCheck.RESERVE_BYTES, StorageCheck.shortfall(freeBytes = 4 * gb, totalBytes = 4 * gb, bytesDone = 0))
    }
}
