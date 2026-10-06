package com.regolith.domain.media

/**
 * Everything in a folder on the share that is not a video: the folder's
 * poster, a film's subtitles and chapters, an `.nfo`, anything else.
 *
 * The Library parses these out. Browse lists them, the way a file manager
 * would: it is the share as it actually is (the owner's rule, 2026-10-05).
 * Pure; the rows are `ShareFileEntity`.
 */
object OtherFiles {

    /** What a file is, for its icon and its line in Browse. */
    enum class Kind(val label: String) {
        PICTURE("Picture"),
        SUBTITLES("Subtitles"),
        CHAPTERS("Chapters"),
        INFO("Info"),
        OTHER("File"),
    }

    private val subtitleExtensions = setOf("srt", "vtt", "ass", "ssa", "sub", "idx", "sup", "smi")
    private val infoExtensions = setOf("nfo", "txt", "md", "json", "xml")

    fun kindOf(name: String): Kind {
        // Before anything by extension: a chapters file is a .txt too.
        if (ChapterSidecar.basenameOf(name) != null) return Kind.CHAPTERS
        val ext = MediaFileTypes.extensionOf(name)
        return when {
            MediaFileTypes.isPhoto(name) -> Kind.PICTURE
            ext in subtitleExtensions -> Kind.SUBTITLES
            ext in infoExtensions -> Kind.INFO
            else -> Kind.OTHER
        }
    }

    /**
     * Whether a file that is not a video is listed at all.
     *
     * Dot-files belong to the system (`.DS_Store`, a Mac's `._beach.mp4`),
     * and a file manager hides them too. `JcifsGateway` already leaves them
     * out of every listing; this holds to it whatever lists the folder. A
     * `.part` file is something still being written, an upload or a poster
     * on its way, and Browse already shows an upload as its own row. It
     * becomes the real file when it lands.
     */
    fun isListed(name: String): Boolean = !name.startsWith(".") && !name.endsWith(PART_SUFFIX, ignoreCase = true)

    /** The suffix `data.smb.PART_SUFFIX` writes with; `domain/` cannot import from `data/`. */
    private const val PART_SUFFIX = ".part"
}
