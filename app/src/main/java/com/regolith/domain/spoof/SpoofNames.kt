package com.regolith.domain.spoof

/**
 * Made-up names for spoof mode: what the library shows in place of the real
 * names on the share, so the app can be shown to someone, recorded or
 * screenshotted without showing what is on it.
 *
 * Every name is worked out from the real one and a [salt] picked once per
 * install, so the same video is "Quiet Lantern" on every screen and every
 * launch, and nothing is stored. A different install, or a new salt, gives
 * different names. Nothing here ever leaves the phone; the stand-in photos
 * are fetched by [imageSeed], which carries no name at all.
 *
 * The names are two words, an adjective and a noun, now and then with "The"
 * in front: they read as titles rather than as noise, which is what a demo
 * needs. Structure is kept where it gives nothing away ("Season 01", a
 * film folder's year, a subtitle's ".en.srt"), so the library still has its
 * shape.
 *
 * Pure: no Android, so it is tested on the JVM. Web analogy: a seeded faker.
 */
object SpoofNames {

    /** A made-up title for [real]: "Quiet Lantern", or "The Quiet Lantern". */
    fun title(real: String, salt: Long): String {
        val h = hash(salt, "title", real)
        val adjective = ADJECTIVES[Math.floorMod(h ushr 8, ADJECTIVES.size.toLong()).toInt()]
        val noun = NOUNS[Math.floorMod(h ushr 24, NOUNS.size.toLong()).toInt()]
        // About one in six gets an article, so a wall is not all one rhythm.
        return if (Math.floorMod(h, 6L) == 0L) "The $adjective $noun" else "$adjective $noun"
    }

    /**
     * A made-up file name for [real], its extension kept: "Arrival.2016.mkv"
     * becomes "Quiet Lantern.mkv". The made-up part is [title] of the name
     * without its extension, so a video's file name and its title agree.
     * A picture a folder uses as its poster keeps its own name ("poster.jpg"):
     * it is the same in every folder and says nothing.
     */
    fun fileName(real: String, salt: Long): String {
        val dot = real.lastIndexOf('.')
        if (dot <= 0) return title(real, salt)
        val base = real.substring(0, dot)
        if (base.lowercase() in SIDECAR_STEMS) return real
        return title(base, salt) + real.substring(dot)
    }

    /**
     * The made-up title of a file whose name is [realFileName]: the same words
     * [fileName] uses, without the extension.
     */
    fun titleOfFile(realFileName: String, salt: Long): String {
        val dot = realFileName.lastIndexOf('.')
        return title(if (dot > 0) realFileName.substring(0, dot) else realFileName, salt)
    }

    /**
     * A made-up name for the folder called [real]. Names that are the same in
     * everyone's library ("Season 01", "Extras", "DCIM") are kept, and a year
     * in brackets at the end ("Arrival (2016)") stays on the made-up name.
     */
    fun folderName(real: String, salt: Long): String {
        val trimmed = real.trim()
        if (trimmed.isEmpty() || isStructural(trimmed)) return real
        val year = TRAILING_YEAR.find(trimmed)
        val stem = year?.let { trimmed.substring(0, it.range.first).trim() }?.ifEmpty { null } ?: trimmed
        val made = title(stem, salt)
        return if (year != null) "$made ${year.value.trim()}" else made
    }

    /**
     * A `/`-separated path with every folder in it made up as [folderName]
     * makes it, and a last part with an extension made up as [fileName]: the
     * same words the folders' and files' own tiles wear.
     */
    fun path(real: String, salt: Long): String {
        if (real.isEmpty()) return real
        val parts = real.split('/')
        return parts.mapIndexed { index, part ->
            when {
                part.isEmpty() -> part
                index == parts.lastIndex && part.lastIndexOf('.') > 0 -> fileName(part, salt)
                else -> folderName(part, salt)
            }
        }.joinToString("/")
    }

    /**
     * What a stand-in photo is fetched by: a hex string made from [ownerKey]
     * (a database id, never a name) and the [salt]. The photo service sees
     * this and nothing else, and two installs see different photos.
     */
    fun imageSeed(ownerKey: String, salt: Long): String = "rg" + java.lang.Long.toHexString(hash(salt, "image", ownerKey))

    private fun isStructural(name: String): Boolean = SEASON.matches(name) || name.lowercase() in KEPT_FOLDERS

    /**
     * FNV-1a, 64-bit, over the salt, a kind and the text. Stable across
     * devices and JVMs, unlike `String.hashCode` which is only 32 bits and
     * whose distribution over short names is poor.
     */
    private fun hash(salt: Long, kind: String, text: String): Long {
        var h = -0x340d631b7bdddcdbL // 14695981039346656037, the FNV offset basis
        fun mix(b: Int) {
            h = h xor (b.toLong() and 0xff)
            h *= 0x100000001b3L // the FNV prime
        }
        for (i in 0 until 8) mix((salt ushr (i * 8)).toInt())
        kind.toByteArray(Charsets.UTF_8).forEach { mix(it.toInt()) }
        mix(0)
        text.toByteArray(Charsets.UTF_8).forEach { mix(it.toInt()) }
        // A final avalanche, so neighbouring names do not land on neighbouring words.
        h = h xor (h ushr 33)
        h *= -0xae502812aa7333L
        h = h xor (h ushr 33)
        return h
    }

    private val SEASON = Regex("""^(season|series|staffel|saison|temporada|stagione)\s*\d+$""", RegexOption.IGNORE_CASE)
    private val TRAILING_YEAR = Regex("""\s*\((19|20)\d{2}\)$""")

    /** Folder names that are the same in everyone's library, and so give nothing away. */
    private val KEPT_FOLDERS = setOf(
        "specials", "extras", "featurettes", "trailers", "behind the scenes", "deleted scenes",
        "dcim", "camera", "movies", "download", "downloads", "pictures", "screenshots",
        "screen recordings", "screenrecorder", "video", "videos",
    )

    /** The names a folder's own pictures go by ([com.regolith.domain.artwork.ArtworkCandidates]). */
    private val SIDECAR_STEMS = setOf("poster", "folder", "cover", "thumb", "backdrop", "fanart", "banner")

    private val ADJECTIVES = listOf(
        "Amber", "Ashen", "Autumn", "Azure", "Bitter", "Blue", "Bright", "Broken", "Burning", "Calm",
        "Cedar", "Copper", "Coral", "Crimson", "Crystal", "Distant", "Drifting", "Dusty", "Early", "Echoing",
        "Electric", "Emerald", "Endless", "Evening", "Faded", "Fallen", "Far", "First", "Fleeting", "Floating",
        "Forgotten", "Frozen", "Gentle", "Gilded", "Glass", "Golden", "Granite", "Gray", "Hazy", "Hidden",
        "Hollow", "Indigo", "Iron", "Ivory", "Jade", "Last", "Late", "Lone", "Lost", "Lunar",
        "Marble", "Midnight", "Misty", "Morning", "Neon", "Northern", "Old", "Open", "Pale", "Paper",
        "Quiet", "Rapid", "Restless", "Rising", "Roaming", "Rusty", "Salt", "Scarlet", "Secret", "Shining",
        "Silent", "Silver", "Slow", "Small", "Solar", "Southern", "Spring", "Still", "Stone", "Summer",
        "Sunlit", "Swift", "Tall", "Tidal", "Velvet", "Violet", "Wandering", "Warm", "Western", "White",
        "Wild", "Willow", "Winter", "Woven", "Young",
    )

    private val NOUNS = listOf(
        "Anchor", "Archive", "Atlas", "Avenue", "Bay", "Beacon", "Bridge", "Brook", "Canyon", "Cascade",
        "Circuit", "Cliff", "Coast", "Comet", "Compass", "Corridor", "Creek", "Crossing", "Current", "Delta",
        "Desert", "Dock", "Drift", "Dune", "Echo", "Ember", "Field", "Fjord", "Forest", "Fountain",
        "Frontier", "Garden", "Gate", "Glacier", "Grove", "Harbor", "Harvest", "Haven", "Highway", "Hill",
        "Horizon", "Island", "Junction", "Lagoon", "Lake", "Lantern", "Ledge", "Light", "Lighthouse", "Marsh",
        "Meadow", "Mesa", "Mirror", "Moon", "Mountain", "Orbit", "Orchard", "Outpost", "Pass", "Path",
        "Pier", "Pine", "Prairie", "Quarry", "Rain", "Ridge", "River", "Road", "Rooftop", "Sail",
        "Shore", "Signal", "Sky", "Spire", "Square", "Station", "Storm", "Street", "Summit", "Sun",
        "Terrace", "Thicket", "Tide", "Tower", "Trail", "Tunnel", "Valley", "Vista", "Voyage", "Wave",
        "Wharf", "Window", "Woods", "Yard", "Zephyr",
    )
}
