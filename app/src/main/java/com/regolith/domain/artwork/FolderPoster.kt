package com.regolith.domain.artwork

import com.regolith.domain.smb.SmbEntry
import com.regolith.domain.transfer.UploadNames
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A picture from the phone made into a folder's poster (P19): what it is
 * called on the share, which pictures already there it takes over from, and
 * what those are renamed to when they are kept. Pure; the share writes are
 * `FolderPosterWriter`'s and the picture itself is `PosterRepository`'s.
 */
object FolderPoster {

    /**
     * What the uploaded picture is called: `poster.jpg`, the first name
     * Regolith looks for in a folder ([ArtworkCandidates.sidecarStems]) and
     * one every media server reads. Always a JPEG whatever was picked: a
     * phone photo is often HEIF, which none of them reads as a poster.
     */
    val NAME: String = ArtworkKind.POSTER.fileName

    /**
     * The longest side the poster is kept at, in pixels. Far more than a tile
     * needs, so a television reading the same share still gets a sharp poster,
     * and small enough to stay well under the 8 MB a folder picture may be
     * ([ArtworkCandidates.MAX_IMAGE_BYTES]) — a phone photo can be bigger
     * than that as it comes.
     */
    const val MAX_LONG_SIDE = 2000

    /**
     * The pictures in [entries] that already act as the folder's own artwork:
     * every name Regolith takes as one ([ArtworkCandidates.isSidecarName]),
     * whatever its size. One too big for Regolith is still a poster to
     * another player, and still what a new `poster.jpg` would overwrite.
     * In the order Regolith ranks them, so the first is the one it shows.
     */
    fun existing(entries: List<SmbEntry>): List<SmbEntry> =
        entries.filter { !it.isDirectory && ArtworkCandidates.isSidecarName(it.name) }
            .sortedWith(
                compareBy(
                    { ArtworkCandidates.sidecarStems.indexOf(it.name.substringBeforeLast('.').lowercase()) },
                    { ArtworkCandidates.imageExtensions.indexOf(it.name.substringAfterLast('.').lowercase()) },
                ),
            )

    /**
     * New names for the [existing] pictures when they are kept: the app's own
     * "keep both" numbering ([UploadNames.keepBoth]), so `folder.jpg` becomes
     * `folder (1).jpg`, which no player takes for artwork. Never a name in
     * [taken] (the folder's listing), the new poster's own, or one already
     * given to another picture here.
     */
    fun keptNames(existing: List<String>, taken: Collection<String>): Map<String, String> {
        val used = taken.toMutableSet().apply { add(NAME) }
        return existing.associateWith { name -> UploadNames.keepBoth(name, used + name).also { used += it } }
    }

    /** The size to decode a [width] × [height] picture at: no more than [MAX_LONG_SIDE] on its long side, and never enlarged. */
    fun targetSize(width: Int, height: Int): Pair<Int, Int> {
        val long = max(width, height)
        if (long <= MAX_LONG_SIDE) return width to height
        val f = MAX_LONG_SIDE.toFloat() / long
        return max(1, (width * f).roundToInt()) to max(1, (height * f).roundToInt())
    }
}

/** What happens to the pictures a new folder poster takes over from. */
enum class ExistingArtwork {
    /** They are deleted: the new poster is the folder's only picture. */
    REPLACE,

    /** They stay, renamed out of the way ([FolderPoster.keptNames]) so they no longer count as artwork. */
    KEEP,
}

/** How uploading a folder poster went. */
enum class FolderPosterOutcome {
    SAVED,

    /** The picture could not be read or decoded on the phone; nothing was sent. */
    UNREADABLE,

    /** The share or the account will not take writes. */
    READ_ONLY,

    /** The server could not be reached. */
    UNREACHABLE,

    FAILED,
}
