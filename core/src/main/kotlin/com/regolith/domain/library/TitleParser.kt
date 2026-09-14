package com.regolith.domain.library

/**
 * What a filename or folder name says about a title (design section 08).
 * Local parsing only: nothing is looked up online. Years and `SxxEyy` are
 * what pins a title; everything after them is release noise.
 */
data class ParsedName(
    /** Cleaned title: dots and underscores become spaces, release tags dropped. */
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
) {
    /** True when the name carried a year or an episode number; false is the design's "No match". */
    val matched: Boolean get() = year != null || episode != null

    /** "Arrival (2016)", "Severance S1E1", or the bare title. */
    val display: String
        get() = when {
            episode != null -> "$title S${season ?: 1}E$episode"
            year != null -> "$title ($year)"
            else -> title
        }
}

object TitleParser {
    private val titleYear = Regex("""^(.+?)\s*[(\[](\d{4})[)\]]\s*$""")
    private val episodeTag = Regex("""(?i)(?:^|[\s._-])S(\d{1,2})[\s._-]?E(\d{1,3})(?=$|[\s._-])""")
    private val altEpisodeTag = Regex("""(?i)(?:^|[\s._-])(\d{1,2})x(\d{2,3})(?=$|[\s._-])""")
    private val seasonFolder = Regex("""(?i)^(?:season|series|s)\s*0*(\d{1,2})$""")
    private val yearToken = Regex("""^(19|20)\d{2}$""")
    private val resolutionToken = Regex("""(?i)^(\d{3,4}[pi]|4k|uhd|8k)$""")

    /** Tokens that mark the end of the title in scene-style names. */
    private val releaseTags = setOf(
        "x264", "x265", "h264", "h265", "h.264", "h.265", "hevc", "avc", "av1", "xvid", "divx",
        "bluray", "blu-ray", "bdrip", "brrip", "bdremux", "remux", "webrip", "web-dl", "webdl", "web", "hdtv", "dvdrip", "dvd",
        "hdr", "hdr10", "hdr10+", "dv", "dovi", "sdr", "10bit", "8bit",
        "aac", "ac3", "eac3", "dts", "dts-hd", "truehd", "atmos", "flac", "opus", "ddp", "dd5", "dd",
        "proper", "repack", "internal", "extended", "unrated", "remastered", "criterion", "imax", "multi", "dual", "sub", "subs",
    )

    /** A video file name (with or without extension). */
    fun parseVideoName(fileName: String): ParsedName {
        val stem = stripExtension(fileName)
        episodeTag.find(stem)?.let { m ->
            return ParsedName(cleanTitle(stem.substring(0, m.range.first)), season = m.groupValues[1].toInt(), episode = m.groupValues[2].toInt())
        }
        altEpisodeTag.find(stem)?.let { m ->
            return ParsedName(cleanTitle(stem.substring(0, m.range.first)), season = m.groupValues[1].toInt(), episode = m.groupValues[2].toInt())
        }
        return parseTitleYear(stem)
    }

    /** A folder name: `Arrival (2016)`, `Severance`, `Season 01`. */
    fun parseFolderName(name: String): ParsedName = parseTitleYear(name.trim())

    /** The season number of a `Season 01` / `S01` folder, or null. */
    fun seasonNumber(folderName: String): Int? = seasonFolder.find(folderName.trim())?.groupValues?.get(1)?.toInt()

    private fun parseTitleYear(text: String): ParsedName {
        titleYear.find(text)?.let { m -> return ParsedName(cleanTitle(m.groupValues[1]), year = m.groupValues[2].toInt()) }
        val tokens = tokenize(text)
        var cut = tokens.size
        var year: Int? = null
        for ((i, t) in tokens.withIndex()) {
            if (i == 0) continue // a name can start with a year ("2001 A Space Odyssey")
            if (yearToken.matches(t) && (i == tokens.lastIndex || i > 0)) {
                year = t.toInt(); cut = i; break
            }
            if (resolutionToken.matches(t) || t.lowercase() in releaseTags) {
                cut = i; break
            }
        }
        val title = cleanTitle(tokens.take(cut).joinToString(" ")).ifEmpty { cleanTitle(text) }
        return ParsedName(title, year = year)
    }

    private fun stripExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && name.length - dot <= 5) name.substring(0, dot) else name
    }

    /** Split on the separators scene names use; a hyphen only when it is padded by spaces or dots ("-GRP" is a suffix). */
    private fun tokenize(text: String): List<String> =
        text.replace('_', ' ').replace('.', ' ').replace(Regex("""\s+-\s+"""), " ").replace(Regex("""-(?=[A-Za-z0-9]+$)"""), " ")
            .split(' ').map { it.trim() }.filter { it.isNotEmpty() }

    private fun cleanTitle(raw: String): String =
        raw.replace('_', ' ').replace('.', ' ').replace(Regex("""\s*-\s*$"""), "").replace(Regex("""\s+"""), " ").trim()
}
