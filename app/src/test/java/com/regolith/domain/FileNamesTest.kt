package com.regolith.domain

import com.regolith.domain.fileops.FileNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The name rules: the last thing standing between something typed into a
 * dialog and a file nobody can open. Pure logic, so it is checked here
 * rather than by round-tripping a real share.
 */
class FileNamesTest {

    @Test
    fun `a plain name comes back trimmed`() {
        assertEquals("Heat 1995", FileNames.cleanBase("  Heat 1995  "))
    }

    @Test
    fun `dots inside a name are fine`() {
        assertEquals("Heat.1995.1080p", FileNames.cleanBase("Heat.1995.1080p"))
    }

    @Test
    fun `path separators are refused rather than sanitised away`() {
        // Dropping the slash would put the file somewhere the user never
        // asked for, which is worse than saying no.
        assertNull(FileNames.cleanBase("Films/Heat"))
        assertNull(FileNames.cleanBase("Films\\Heat"))
    }

    @Test
    fun `characters Windows cannot open are refused`() {
        for (bad in listOf("a:b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b")) {
            assertNull("accepted $bad", FileNames.cleanBase(bad))
        }
    }

    @Test
    fun `a trailing dot is refused, because Windows would drop it`() {
        assertNull(FileNames.cleanBase("Heat."))
    }

    @Test
    fun `a trailing space is trimmed, not refused`() {
        assertEquals("Heat", FileNames.cleanBase("Heat "))
    }

    @Test
    fun `empty, dot and dot-dot are refused`() {
        assertNull(FileNames.cleanBase(""))
        assertNull(FileNames.cleanBase("   "))
        assertNull(FileNames.cleanBase("."))
        assertNull(FileNames.cleanBase(".."))
    }

    @Test
    fun `a name longer than the cap is refused`() {
        assertNull(FileNames.cleanBase("a".repeat(FileNames.MAX_BASE + 1)))
        assertEquals("a".repeat(FileNames.MAX_BASE), FileNames.cleanBase("a".repeat(FileNames.MAX_BASE)))
    }

    @Test
    fun `control characters are refused`() {
        assertNull(FileNames.cleanBase("Heat\u0007and"))
        assertNull(FileNames.cleanBase("Heat\nand"))
    }

    @Test
    fun `the extension is put back on, and a file without one keeps none`() {
        assertEquals("Heat.mkv", FileNames.withExtension("Heat", "mkv"))
        assertEquals("Heat", FileNames.withExtension("Heat", ""))
    }

    @Test
    fun `the editable part is everything before the last dot`() {
        assertEquals("Heat.1995.1080p", FileNames.baseOf("Heat.1995.1080p.mkv"))
        // No extension: the whole name is editable, rather than an empty field.
        assertEquals("README", FileNames.baseOf("README"))
    }
}
