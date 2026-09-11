package com.regolith.domain

import com.regolith.domain.model.rootsCover
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides what a narrowed share lets the app read. Everything
 * downstream leans on it: the scan asks it before listing a folder, and
 * Browse asks it again every time you open one.
 */
class RootsCoverTest {

    @Test
    fun `no chosen folders means the whole share`() {
        assertTrue(rootsCover(emptyList(), ""))
        assertTrue(rootsCover(emptyList(), "Anything"))
        assertTrue(rootsCover(emptyList(), "Anything/At/Any/Depth"))
    }

    @Test
    fun `a chosen folder covers itself and everything under it`() {
        val roots = listOf("Films")
        assertTrue(rootsCover(roots, "Films"))
        assertTrue(rootsCover(roots, "Films/Arrival (2016)"))
        assertTrue(rootsCover(roots, "Films/Arrival (2016)/Extras"))
    }

    @Test
    fun `the share root is not covered, so it is never listed`() {
        // This is what stops a narrowed share from being walked from the top.
        assertFalse(rootsCover(listOf("Films"), ""))
    }

    @Test
    fun `a folder on the way down to a pick is not covered`() {
        val roots = listOf("Series/Severance/Season 02")
        assertFalse(rootsCover(roots, "Series"))
        assertFalse(rootsCover(roots, "Series/Severance"))
        assertTrue(rootsCover(roots, "Series/Severance/Season 02"))
        assertTrue(rootsCover(roots, "Series/Severance/Season 02/Extras"))
    }

    @Test
    fun `a sibling of a pick stays out`() {
        val roots = listOf("Series/Severance/Season 02")
        assertFalse(rootsCover(roots, "Series/Severance/Season 01"))
        assertFalse(rootsCover(roots, "Series/The Bear"))
    }

    @Test
    fun `picks at different depths all count`() {
        val roots = listOf("Films", "Series/Severance/Season 02", "Series/The Bear")
        assertTrue(rootsCover(roots, "Films/Heat.1995.mkv"))
        assertTrue(rootsCover(roots, "Series/Severance/Season 02"))
        assertTrue(rootsCover(roots, "Series/The Bear/Season 01"))
        assertFalse(rootsCover(roots, "Archive"))
        assertFalse(rootsCover(roots, "Series/Severance/Season 01"))
    }

    @Test
    fun `a name that merely starts the same is not inside`() {
        // "Films Archive" is not in "Films", and only the separator says so.
        val roots = listOf("Films")
        assertFalse(rootsCover(roots, "Films Archive"))
        assertFalse(rootsCover(roots, "FilmsOld/Heat.mkv"))
    }
}
