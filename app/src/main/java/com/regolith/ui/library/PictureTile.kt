package com.regolith.ui.library

import com.regolith.data.db.ShareFileEntity
import com.regolith.domain.artwork.ArtworkCandidates
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.domain.library.PictureKeys
import com.regolith.domain.library.PictureOrder
import com.regolith.domain.library.comparator
import com.regolith.domain.media.MediaFileTypes

/**
 * One picture on the share, as a collection's Images tab, a wall and the
 * lightbox show it (schema v17): its own thumbnail at its own shape, and
 * the two marks a picture can carry — it is the collection's poster, or it
 * is a video's own picture (`beach.jpg` beside `beach.mp4`).
 */
data class PictureTile(
    val pictureId: Long,
    val folderId: Long,
    val shareId: Long,
    /** `/`-joined path inside the share. */
    val relPath: String,
    /** The file's name, extension and all ("IMG_4821.HEIC"). */
    override val name: String,
    /** As shown: the EXIF or HEIF turn applied. Null until its header has been read. */
    val width: Int?,
    val height: Int?,
    override val takenAtMs: Long?,
    override val modifiedAtMs: Long,
    override val addedAtMs: Long?,
    val sizeBytes: Long,
    /** "Pixel 8 · f/1.7", when the picture says. */
    val camera: String?,
    /** One of the collection's own pictures (poster.jpg, folder.jpg…): editing it changes the poster. */
    val poster: Boolean = false,
    /** The video it is the picture of, named as that video's tile is; null for a picture of its own. */
    val videoName: String? = null,
) : PictureKeys {
    /** A GIF moves in the lightbox and a story; in the mosaic it is its first frame, marked. */
    val gif: Boolean get() = MediaFileTypes.extensionOf(name) == "gif"

    /** Its name without the extension, for a caption: "IMG_4821". */
    val title: String get() = name.substringBeforeLast('.')

    /** Width over height as shown, or square while it has not been measured. */
    val aspect: Float
        get() = if (width != null && height != null && width > 0 && height > 0) width.toFloat() / height else 1f

    val artwork: ArtworkRequest get() = ArtworkRequest(ArtworkOwner.Picture(pictureId), ArtworkKind.PICTURE)

    val testTag: String get() = "library_picture_$pictureId"
}

/**
 * The pictures among a folder's other files, as tiles: everything the
 * Library shows as a picture ([MediaFileTypes.isPicture]), marked as the
 * collection's poster when it is one of its sidecars, or with the name of
 * the video it belongs to when it shares that video's base name.
 * [videoNames] maps each video's file name in the same folder to the name
 * its tile shows.
 */
internal fun pictureTiles(files: List<ShareFileEntity>, videoNames: Map<String, String>): List<PictureTile> {
    val byStem = videoNames.entries.associate { (file, shown) -> file.substringBeforeLast('.').lowercase() to shown }
    // The one the collection actually wears: the first sidecar in the order
    // the artwork resolver tries them (poster before folder, .jpg before .png).
    val posters = files.filter { ArtworkCandidates.isSidecarName(it.name) }.groupBy { it.folderId }.mapValues { (_, sidecars) ->
        ArtworkCandidates.sidecarStems.asSequence()
            .flatMap { stem -> ArtworkCandidates.imageExtensions.asSequence().map { "$stem.$it" } }
            .firstNotNullOfOrNull { candidate -> sidecars.firstOrNull { it.name.equals(candidate, ignoreCase = true) }?.id }
    }
    return files.filter { MediaFileTypes.isPicture(it.name) }.map { row ->
        PictureTile(
            pictureId = row.id,
            folderId = row.folderId,
            shareId = row.shareId,
            relPath = row.relPath,
            name = row.name,
            width = row.width,
            height = row.height,
            takenAtMs = row.takenAtMs,
            modifiedAtMs = row.modifiedAtMs,
            addedAtMs = row.addedAtMs,
            sizeBytes = row.sizeBytes,
            camera = row.camera,
            poster = posters[row.folderId] == row.id,
            videoName = byStem[row.name.substringBeforeLast('.').lowercase()],
        )
    }
}

/** The pictures in [order], the same order everywhere they are shown: the tab, the lightbox, a story. */
internal fun List<PictureTile>.inOrder(order: PictureOrder): List<PictureTile> = sortedWith(order.comparator())
