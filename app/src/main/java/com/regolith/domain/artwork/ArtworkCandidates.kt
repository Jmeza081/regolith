package com.regolith.domain.artwork

import com.regolith.domain.media.MediaFileTypes
import com.regolith.domain.smb.SmbEntry

/**
 * The image files worth trying for a video or a folder, in the order the
 * design fixes (section 08, "Thumbnail source order"). Pure: it looks at
 * one directory listing and returns names; reading the bytes is the
 * repository's job. Steps 3 to 5 (embedded cover, frame grab, placeholder)
 * need the video itself and are not decided here.
 */
object ArtworkCandidates {
    /**
     * Formats the design accepts, matched case-insensitively, plus GIF.
     *
     * A GIF is a folder picture like any other, shown as its first frame.
     * Only a top-level folder's poster moves ([AnimatedPoster]). It comes
     * last, so a `poster.jpg` beside a `poster.gif` still wins, as before.
     */
    val imageExtensions = listOf("jpg", "jpeg", "png", "webp", "gif")

    /** Sidecar stems, most to least specific. */
    val sidecarStems = listOf("poster", "folder", "cover", "thumb")

    /** "≤ 8 MB per image": anything bigger is skipped, not downsampled. */
    const val MAX_IMAGE_BYTES = 8L * 1024 * 1024

    /**
     * Where in the file to grab the frame: the midpoint.
     *
     * It used to be 10%, which is where a title card or a studio ident
     * usually still is. A folder of episodes that share an intro then
     * produced a folder of identical tiles. Half way in is past every
     * intro and well short of the credits, so sibling episodes get
     * visibly different frames.
     */
    fun framePositionMs(durationMs: Long): Long = (durationMs / 2).coerceAtLeast(0)

    /**
     * Where the [MOSAIC_CELLS] frames of a folder mosaic come from within
     * ONE of its videos, when the folder holds fewer videos than cells:
     * evenly spread across the middle of the runtime.
     */
    fun mosaicPositionsMs(durationMs: Long, count: Int): List<Long> {
        if (durationMs <= 0 || count <= 0) return emptyList()
        if (count == 1) return listOf(framePositionMs(durationMs))
        val start = (durationMs * 0.20).toLong()
        val end = (durationMs * 0.80).toLong()
        val step = (end - start) / (count - 1)
        return List(count) { start + step * it }
    }

    /** A folder mosaic is 2x2: four frames is enough to read as "these films", and costs four seeks. */
    const val MOSAIC_COLUMNS = 2
    const val MOSAIC_ROWS = 2
    const val MOSAIC_CELLS = MOSAIC_COLUMNS * MOSAIC_ROWS

    data class Candidate(val name: String, val source: ArtworkSource)

    fun isImage(name: String): Boolean = MediaFileTypes.extensionOf(name) in imageExtensions

    /** True for a name that is a folder's own picture whatever its size: one of [sidecarStems] in one of [imageExtensions]. */
    fun isSidecarName(name: String): Boolean =
        isImage(name) && name.substringBeforeLast('.').lowercase() in sidecarStems

    /**
     * Candidates for a video, given the entries of the folder it is in: its
     * basename image (`beach.jpg` beside `beach.mp4`), and nothing else.
     *
     * A folder's own pictures (`poster.jpg`, `folder.jpg`…) belong to the
     * folder, never to a video in it, not even its only video (the owner's
     * model, 2026-10-05). A collection of one may gain videos later, and a
     * video's picture shouldn't change the day a second one arrives. With no
     * basename image, a video's picture comes from the video itself (the
     * embedded cover, else a frame), like every other video's.
     */
    fun forFile(fileName: String, siblings: List<SmbEntry>): List<Candidate> {
        val images = siblings.filter { !it.isDirectory && isImage(it.name) && it.sizeBytes <= MAX_IMAGE_BYTES }
        val out = mutableListOf<Candidate>()
        val stem = fileName.substringBeforeLast('.')
        for (ext in imageExtensions) {
            images.firstOrNull { it.name.equals("$stem.$ext", ignoreCase = true) }?.let { out += Candidate(it.name, ArtworkSource.BASENAME) }
        }
        return out
    }

    /** Candidates for a folder (collection, show, season): its own sidecars only. */
    fun forFolder(entries: List<SmbEntry>): List<Candidate> =
        sidecars(entries.filter { !it.isDirectory && isImage(it.name) && it.sizeBytes <= MAX_IMAGE_BYTES })

    private fun sidecars(images: List<SmbEntry>): List<Candidate> {
        val out = mutableListOf<Candidate>()
        for (stem in sidecarStems) {
            for (ext in imageExtensions) {
                images.firstOrNull { it.name.equals("$stem.$ext", ignoreCase = true) }?.let { out += Candidate(it.name, ArtworkSource.SIDECAR) }
            }
        }
        return out
    }
}
