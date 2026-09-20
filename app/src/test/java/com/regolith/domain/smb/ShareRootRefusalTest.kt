package com.regolith.domain.smb

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [shareRootRefusal] decides when "not found" is the wrong thing to say.
 *
 * The distinction is narrow and easy to break: a share's own root is the one
 * place "it is not there" cannot be true, because the tree connect that got
 * us there already proved it is. One path segment either side of that line
 * and the default wording is correct again.
 */
class ShareRootRefusalTest {

    @Test
    fun `a share root gets the server-permission explanation`() {
        val message = shareRootRefusal("Regolith/")
        assertTrue("names the share", message!!.startsWith("Regolith opened"))
        // The actionable half is the point: the owner's afternoon went on a
        // macOS TCC grant that "not found" gave no hint of.
        assertTrue("says where the fault is", message.contains("permission on the server"))
        assertTrue("says what to do about it", message.contains("Full Disk Access"))
    }

    @Test
    fun `a path inside the share keeps the plain wording`() {
        // Genuinely missing, and saying so is right.
        assertNull(shareRootRefusal("Regolith/Films"))
        assertNull(shareRootRefusal("Regolith/Films/"))
        assertNull(shareRootRefusal("Regolith/Films/Arrival.2016.mkv"))
    }

    @Test
    fun `a file at the share root keeps the plain wording`() {
        // No trailing slash: this is a file being opened, not a directory
        // being listed, so a missing name means what it says.
        assertNull(shareRootRefusal("Regolith"))
    }

    @Test
    fun `nonsense in, nothing out`() {
        assertNull(shareRootRefusal(""))
        assertNull(shareRootRefusal("/"))
    }
}
