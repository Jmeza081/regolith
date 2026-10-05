package com.regolith.domain.media

import com.regolith.domain.artwork.ArtworkCandidates

/**
 * A video's companion files: the files beside it that share its base name,
 * `beach.mp4` with `beach.chapters.txt`, `beach.en.srt`, `beach.jpg` and
 * `beach.nfo`. A video and its companions are one thing to the owner
 * (decision 1 of `docs/LIBRARY_EDITING_PLAN.md`), so they are renamed,
 * moved and deleted together.
 *
 * Pure, and deliberately cautious. A file is only ever a companion of ONE
 * video, and when that is in doubt it belongs to none and stays put:
 * leaving a subtitles file behind is a small problem, taking the wrong one
 * along is a confusing one.
 */
object Companions {

    /** The part of a video's name its companions start with: the name without its extension. */
    fun baseOf(videoName: String): String = videoName.substringBeforeLast('.')

    /**
     * The names in [folderNames] (one folder's listing, which may include
     * [videoName] itself) that are [videoName]'s companions.
     *
     * - A companion starts with the video's base and a dot, ignoring case,
     *   as names on a share do.
     * - Another video is never a companion.
     * - The folder's own picture (`poster.jpg`, `folder.jpg`…) belongs to the
     *   folder, even beside a video that happens to be called `poster.mp4`.
     * - The longest base wins: `beach.2019.en.srt` belongs to
     *   `beach.2019.mp4`, not to `beach.mp4`.
     * - Two videos with the same base (`beach.mp4` and `beach.mkv`) share
     *   their companions, so neither takes them anywhere.
     */
    fun of(videoName: String, folderNames: Collection<String>): List<String> {
        if (!MediaFileTypes.isVideo(videoName)) return emptyList()
        val base = baseOf(videoName).lowercase()
        val otherVideoBases = folderNames
            .filter { MediaFileTypes.isVideo(it) && !it.equals(videoName, ignoreCase = true) }
            .map { baseOf(it).lowercase() }
        if (base in otherVideoBases) return emptyList()
        val longer = otherVideoBases.filter { it.length > base.length && it.startsWith("$base.") }
        return folderNames.filter { name ->
            val lower = name.lowercase()
            lower.startsWith("$base.") &&
                !MediaFileTypes.isVideo(name) &&
                !ArtworkCandidates.isSidecarName(name) &&
                longer.none { lower.startsWith("$it.") }
        }
    }

    /**
     * Every companion that goes when the videos in [going] (all in one
     * folder, in the order they go) go together: [of] for each, against
     * what is left of [folderNames] once the ones before it have gone with
     * theirs. So two videos sharing a base name take their shared
     * companions only when both go, along with the second. The order is
     * the one `FileOpsRepository` works in, so a dialog that counts with
     * this counts what the operation does.
     */
    fun goingWith(going: List<String>, folderNames: Collection<String>): List<String> {
        val left = folderNames.toMutableList()
        val out = mutableListOf<String>()
        for (video in going) {
            val companions = of(video, left)
            out += companions
            left.remove(video)
            left.removeAll(companions)
        }
        return out
    }

    /**
     * What [companion] is called once its video goes from [oldVideoName] to
     * [newVideoName]: the new base, then everything after the old one.
     * `beach.en.srt` follows `beach.mp4` to `sunset.mp4` as `sunset.en.srt`.
     */
    fun renamed(companion: String, oldVideoName: String, newVideoName: String): String =
        baseOf(newVideoName) + companion.substring(baseOf(oldVideoName).length)
}
