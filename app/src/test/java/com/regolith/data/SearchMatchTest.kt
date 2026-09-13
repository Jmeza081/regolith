package com.regolith.data

import com.regolith.data.repository.LibraryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchMatchTest {
    @Test fun `words become prefix terms`() {
        assertEquals("\"samou*\"", LibraryRepository.ftsMatch("samou"))
        assertEquals("\"le*\" \"samourai*\" \"1967*\"", LibraryRepository.ftsMatch("Le.Samourai.1967"))
        assertEquals("\"gh01*\"", LibraryRepository.ftsMatch("  gh01 "))
    }

    @Test fun `nothing to search for`() {
        assertNull(LibraryRepository.ftsMatch(""))
        assertNull(LibraryRepository.ftsMatch(" . - "))
    }

    @Test fun `quotes and stars cannot break the expression`() {
        assertEquals("\"tar*\"", LibraryRepository.ftsMatch("\"tar\""))
        assertEquals("\"tar*\"", LibraryRepository.ftsMatch("ta*r*"))
    }
}
