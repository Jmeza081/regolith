package com.regolith.domain.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The names from design section 08 and the usual release styles. */
class TitleParserTest {
    private fun p(name: String) = TitleParser.parseVideoName(name)

    @Test fun `scene names cut at the year`() {
        assertEquals(ParsedName("Arrival", 2016), p("Arrival.2016.2160p.HEVC.mkv"))
        assertEquals(ParsedName("Hard Boiled", 1992), p("Hard.Boiled.1992.mp4"))
        assertEquals(ParsedName("Le Samourai", 1967), p("Le.Samourai.1967.remux.mkv"))
        assertEquals(ParsedName("The Thing", 1982), p("The.Thing.1982.1080p.BluRay.x264-GROUP.mkv"))
    }

    @Test fun `title year in brackets`() {
        assertEquals(ParsedName("Arrival", 2016), TitleParser.parseFolderName("Arrival (2016)"))
        assertEquals(ParsedName("Stalker", 1979), p("Stalker (1979).mkv"))
        assertEquals(ParsedName("Stalker", 1979), p("Stalker [1979].mkv"))
    }

    @Test fun `release tags without a year still cut the title`() {
        assertEquals(ParsedName("Paris Texas"), p("Paris.Texas.1080p.WEB-DL.x264.mkv"))
        assertEquals(ParsedName("Solaris"), p("Solaris.REMUX.mkv"))
    }

    @Test fun `episodes`() {
        assertEquals(ParsedName("Severance", season = 1, episode = 1), p("Severance.S01E01.mkv"))
        assertEquals(ParsedName("Severance", season = 2, episode = 10), p("Severance - s02e10 - Cold Harbor.mkv"))
        assertEquals(ParsedName("Twin Peaks", season = 1, episode = 2), p("Twin.Peaks.1x02.mkv"))
        assertEquals("Severance S1E1", p("Severance.S01E01.mkv").display)
    }

    @Test fun `a name that is nothing but a name is unmatched`() {
        val gopro = p("GH010423.MP4")
        assertEquals("GH010423", gopro.title)
        assertNull(gopro.year)
        assertFalse(gopro.matched)
        assertEquals(ParsedName("paris texas"), p("paris-texas.mov")) // hyphens read as spaces
        assertTrue(p("Arrival.2016.mkv").matched)
    }

    @Test fun `a leading year is part of the title`() {
        assertEquals(ParsedName("2001 A Space Odyssey", 1968), p("2001.A.Space.Odyssey.1968.mkv"))
    }

    @Test fun `season folders`() {
        assertEquals(1, TitleParser.seasonNumber("Season 01"))
        assertEquals(3, TitleParser.seasonNumber("season 3"))
        assertEquals(2, TitleParser.seasonNumber("S02"))
        assertNull(TitleParser.seasonNumber("Films"))
    }

    @Test fun `display forms`() {
        assertEquals("Arrival (2016)", ParsedName("Arrival", 2016).display)
        assertEquals("GH010423", ParsedName("GH010423").display)
    }
}
