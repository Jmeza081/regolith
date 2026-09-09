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
    /** Formats the design accepts, matched case-insensitively. */
    val imageExtensions = listOf("jpg", "jpeg", "png", "webp")

    /** Sidecar stems, most to least specific. */
    val sidecarStems = listOf("poster", "folder", "cover", "thumb")

    /** "≤ 8 MB per image": anything bigger is skipped, not downsampled. */
    const val MAX_IMAGE_BYTES = 8L * 1024 * 1024

    /** Where in the file to grab the frame: 10% of the runtime. */
    fun framePositionMs(durationMs: Long): Long = (durationMs / 10).coerceAtLeast(0)

    data class Candidate(val name: String, val source: ArtworkSource)

    fun isImage(name: String): Boolean = MediaFileTypes.extensionOf(name) in imageExtensions

    /**
     * Candidates for a video, given the entries of the folder it is in.
     *
     * A sidecar (`poster.jpg` beside the file) only applies when the folder
     * is a title folder, i.e. this is the only video in it. In a folder of
     * many loose files the sidecar is the collection's poster, not this
     * file's: the design shows `Films/Hard.Boiled.1992.mp4` getting a frame
     * grab even though `Films/poster.jpg` exists. A basename image always
     * applies.
     */
    fun forFile(fileName: String, siblings: List<SmbEntry>): List<Candidate> {
        val images = siblings.filter { !it.isDirectory && isImage(it.name) && it.sizeBytes <= MAX_IMAGE_BYTES }
        val videosInFolder = siblings.count { !it.isDirectory && MediaFileTypes.isVideo(it.name) }
        val out = mutableListOf<Candidate>()
        if (videosInFolder <= 1) {
            out += sidecars(images)
        }
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
