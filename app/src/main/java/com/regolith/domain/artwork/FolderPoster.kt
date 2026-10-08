package com.regolith.domain.artwork

import com.regolith.domain.media.MediaFileTypes
import com.regolith.domain.smb.SmbEntry
import com.regolith.domain.transfer.UploadNames
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A folder's poster made from a picture — one from the phone (P19), one
 * already on the share, or a film's frame (P20): what it is called on the
 * share, which pictures already there it takes over from, and what those
 * are renamed to, since they are always kept. Pure; the share writes are
 * `FolderPosterWriter`'s and the pictures themselves `PosterRepository`'s.
 */
object FolderPoster {

    /**
     * What the uploaded picture is called: `poster.jpg`, the first name
     * Regolith looks for in a folder ([ArtworkCandidates.sidecarStems]) and
     * one every media server reads. A JPEG whatever was picked, because a
     * phone photo is often HEIF and none of them reads that as a poster.
     * The one exception is a GIF that can move ([AnimatedPoster]), which
     * goes up unchanged as `poster.gif` ([PickedPoster]).
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
        entries.filter { !it.isDirectory && ArtworkCandidates.isSidecarName(it.name) }.sortedWith(compareBy(RANK) { it.name })

    /** The names among [names] that act as the folder's own artwork, ranked as [existing] ranks them. */
    fun ranked(names: Collection<String>): List<String> = names.filter(ArtworkCandidates::isSidecarName).sortedWith(RANK)

    /** The one the folder wears, of [names]: the first Regolith tries ([ArtworkCandidates.sidecarStems], then [ArtworkCandidates.imageExtensions]). */
    fun worn(names: Collection<String>): String? = ranked(names).firstOrNull()

    /** Poster before folder, then .jpg before .png: the order the artwork resolver tries the names in. */
    private val RANK: Comparator<String> = compareBy(
        { ArtworkCandidates.sidecarStems.indexOf(it.substringBeforeLast('.').lowercase()) },
        { ArtworkCandidates.imageExtensions.indexOf(it.substringAfterLast('.').lowercase()) },
    )

    /**
     * New names for the [existing] pictures when a new poster takes their
     * place. They are always kept (the owner's call, 2026-10-08): each takes
     * the day it stopped being the poster in brackets, so `poster.jpg`
     * becomes `poster (8 Oct).jpg`, an ordinary picture under Images that no
     * player takes for artwork. A second change the same day is numbered the
     * way a kept upload is ([UploadNames.keepBoth]): `poster (8 Oct) (1).jpg`.
     * Never a name in [taken] (the folder's listing), the new poster's own
     * ([name]), or one already given to another picture here.
     */
    fun keptNames(existing: List<String>, taken: Collection<String>, on: LocalDate, name: String = NAME): Map<String, String> {
        val used = taken.toMutableSet().apply { add(name) }
        val day = dayOf(on)
        return existing.associateWith { old ->
            val dot = old.lastIndexOf('.')
            val dated = if (dot > 0) "${old.substring(0, dot)} ($day)${old.substring(dot)}" else "$old ($day)"
            UploadNames.keepBoth(dated, used).also { used += it }
        }
    }

    /**
     * What a new poster called [name] keeps of [entries], the folder's
     * listing: the pictures acting as its artwork ([existing]), each with
     * its dated name ([keptNames]). [leave] is a picture the poster was made
     * from that stays as it is even if it counts as artwork (`folder.jpg`,
     * cropped) — unless it has the new poster's own name, when it is kept
     * like the others.
     */
    fun keeping(entries: List<SmbEntry>, name: String, on: LocalDate, leave: String? = null): Map<String, String> {
        val old = existing(entries).map { it.name }
            .filterNot { leave != null && it.equals(leave, ignoreCase = true) && !it.equals(name, ignoreCase = true) }
        return keptNames(old, entries.map { it.name }, on, name)
    }

    /**
     * "8 Oct", the day in a kept poster's name. English month names on every
     * phone, so the names on a share read the same whoever made them, and
     * hold nothing a share might refuse (some languages' short months end
     * in a dot).
     */
    fun dayOf(on: LocalDate): String = "${on.dayOfMonth} ${on.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"

    /**
     * How a picture already on the share becomes its folder's poster whole,
     * nothing cut away (decision 17): [name] and [sizeBytes] are the
     * picture's, [worn] the picture the folder wears now ([worn]).
     */
    fun whole(name: String, sizeBytes: Long, worn: String?): WholePoster = when {
        worn != null && worn.equals(name, ignoreCase = true) -> WholePoster.ALREADY
        ArtworkCandidates.isImage(name) && sizeBytes <= ArtworkCandidates.MAX_IMAGE_BYTES -> WholePoster.RENAME
        else -> WholePoster.COPY
    }

    /** What [name] is called once it is renamed to be the poster: `poster` and its own extension, `IMG_2041.PNG` becoming `poster.png`. */
    fun renamedTo(name: String): String = "${ArtworkCandidates.sidecarStems.first()}.${MediaFileTypes.extensionOf(name)}"

    /** The size to decode a [width] × [height] picture at: no more than [MAX_LONG_SIDE] on its long side, and never enlarged. */
    fun targetSize(width: Int, height: Int): Pair<Int, Int> {
        val long = max(width, height)
        if (long <= MAX_LONG_SIDE) return width to height
        val f = MAX_LONG_SIDE.toFloat() / long
        return max(1, (width * f).roundToInt()) to max(1, (height * f).roundToInt())
    }
}

/** How a picture on the share becomes the poster with nothing cut away ([FolderPoster.whole]). */
enum class WholePoster {
    /**
     * The picture itself is renamed `poster.<its extension>`: with nothing
     * cut away there is nothing to keep apart. A JPEG, PNG, WebP or GIF no
     * bigger than 8 MB, which every player reads as it is.
     */
    RENAME,

    /**
     * A JPEG copy is written as `poster.jpg` and the picture stays: a HEIC or
     * AVIF that players will not read as a poster, or one over the 8 MB a
     * folder picture may be ([ArtworkCandidates.MAX_IMAGE_BYTES]).
     */
    COPY,

    /** It is the folder's poster already; there is nothing to do. */
    ALREADY,
}

/** How uploading a folder poster went. */
enum class FolderPosterOutcome {
    SAVED,

    /** Saved, but a GIF went up as a still `poster.jpg`: it is over 8 MB ([PickedPoster.STILL_TOO_BIG]). */
    SAVED_STILL_TOO_BIG,

    /** Saved, but a GIF went up as a still `poster.jpg`: posters below the top level do not move ([PickedPoster.STILL_NESTED]). */
    SAVED_STILL_NESTED,

    /** The picture could not be read or decoded on the phone; nothing was sent. */
    UNREADABLE,

    /** The share or the account will not take writes. */
    READ_ONLY,

    /** The server could not be reached. */
    UNREACHABLE,

    FAILED,
}
