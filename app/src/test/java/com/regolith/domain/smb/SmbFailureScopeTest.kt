package com.regolith.domain.smb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule a batch walk lives or dies by.
 *
 * The artwork pass used to abandon the whole library on ANY [SmbFailure],
 * so one file the server would not open stopped every picture after it:
 * WorkManager retried, the walk skipped what was already cached, reached the
 * same file, and gave up again — a pass that restarts for ever and never
 * gets past the same image.
 */
class SmbFailureScopeTest {
    @Test
    fun `a server out of reach stops the walk`() {
        assertTrue(SmbFailure.Unreachable("tower").isAboutTheServer)
    }

    @Test
    fun `a rejected login stops the walk`() {
        assertTrue(SmbFailure.AuthFailed().isAboutTheServer)
    }

    @Test
    fun `a file that is no longer there does not`() {
        assertFalse(
            "deleted or renamed since the scan listed it — the next file is fine",
            SmbFailure.NotFound("Films/gone.mkv").isAboutTheServer,
        )
    }

    @Test
    fun `a file under an ACL this login cannot read does not`() {
        assertFalse(
            "one folder's permissions say nothing about the share",
            SmbFailure.Forbidden("Films/private").isAboutTheServer,
        )
    }

    @Test
    fun `a refusal the server did not explain does not`() {
        assertFalse(
            "the commonest real cause is a locked or in-use file",
            SmbFailure.Other("STATUS_SHARING_VIOLATION").isAboutTheServer,
        )
    }
}
