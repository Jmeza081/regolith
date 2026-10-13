package com.regolith.data.artwork

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.regolith.data.db.ArtworkEntity
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.domain.artwork.ArtworkSource
import com.regolith.domain.playback.CachedStill
import com.regolith.domain.playback.ChapterPictures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The chapter sheet's pictures, out of the artwork cache: each chapter's
 * frame is a moment frame ([ArtworkOwner.Moment]) kept under the time it was
 * taken from, and the film's own thumb stands in for one that is missing.
 *
 * Decoded here into bitmaps, not handed to Coil the way a tile's picture is,
 * because the point of waiting for them is that the sheet draws every one on
 * its FIRST frame. An image loader starts loading once the tile is laid out,
 * so even a picture already on disk arrives a frame or two after the sheet.
 * Twelve 320x180 thumbs are under 3 MB, and they go when the player does.
 */
class ArtworkChapterPictures @Inject constructor(
    private val artwork: ArtworkRepository,
) : ChapterPictures<Bitmap> {

    // Off the main thread throughout: the cache checks its file is still on
    // disk, and the decode reads it.
    override suspend fun cached(fileId: Long, timeMs: Long): CachedStill<Bitmap>? = withContext(Dispatchers.IO) {
        val row = artwork.cached(ArtworkRequest(ArtworkOwner.Moment(fileId, timeMs), ArtworkKind.THUMB)) ?: return@withContext null
        // A file that will not decode is as good as no frame: the tile takes
        // the film's picture rather than staying blank.
        decode(row)?.let { CachedStill.Picture(it) } ?: CachedStill.Nothing
    }

    override suspend fun make(fileId: Long, timesMs: List<Long>) = artwork.makeMoments(fileId, timesMs)

    override suspend fun standIn(fileId: Long): Bitmap? = withContext(Dispatchers.IO) {
        artwork.cached(ArtworkRequest(ArtworkOwner.File(fileId), ArtworkKind.THUMB))?.let { decode(it) }
    }

    /** Null for a placeholder, which has no picture, and for a file that will not decode. */
    private fun decode(row: ArtworkEntity): Bitmap? {
        if (row.source == ArtworkSource.PLACEHOLDER.name || row.relPath.isEmpty()) return null
        return runCatching { BitmapFactory.decodeFile(artwork.fileFor(row).path) }.getOrNull()
    }
}
