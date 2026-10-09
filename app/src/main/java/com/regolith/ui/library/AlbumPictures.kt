package com.regolith.ui.library

import com.regolith.data.prefs.AppPreferences
import com.regolith.data.repository.LibraryRepository
import com.regolith.data.spoof.SpoofMode
import com.regolith.data.spoof.spoofed
import com.regolith.domain.library.comparator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * One folder's pictures in exactly the order the screen they came from
 * showed them, for the lightbox and a story: the Images tab's order
 * ([com.regolith.domain.library.PictureOrder]), or, for pictures lying loose
 * beside albums ([observe]'s `onWall`), the wall's own. Both orders are read
 * from the settings rather than handed over, so they survive the app being
 * killed and brought back. Spoof mode's names come through as everywhere.
 *
 * Web analogy: a selector shared by two pages that list the same records.
 */
class AlbumPictures @Inject constructor(
    private val library: LibraryRepository,
    private val prefs: AppPreferences,
    private val spoof: SpoofMode,
) {
    /** A folder as the lightbox and a story need it: its name, the share it is on, and its pictures in order. */
    data class Album(val name: String, val shareId: Long?, val pictures: List<PictureTile>)

    fun observe(folderId: Long, onWall: Boolean): Flow<Album> {
        val folder = library.observeFolder(folderId).spoofed(spoof) { it?.let(::folder) }
        val others = library.observeOtherFilesIn(folderId).spoofed(spoof) { otherFiles(it) }
        val videos = library.observeFilesIn(listOf(folderId)).spoofed(spoof) { files(it) }
        val order: Flow<(List<PictureTile>) -> List<PictureTile>> = if (onWall) {
            prefs.libraryOrder.map { o -> { tiles -> tiles.map { LibraryTile.Picture(it) }.sortedWith(o.comparator(addedByDay = true)).map { it.picture } } }
        } else {
            prefs.pictureOrder.map { o -> { tiles -> tiles.inOrder(o) } }
        }
        return combine(folder, others, videos, order) { f, os, vs, sort ->
            // A video's own picture is named for the video as its tile names it.
            val shown = vs.associate { it.name to it.tileName() }
            Album(name = f?.name.orEmpty(), shareId = f?.shareId, pictures = sort(pictureTiles(os, shown)))
        }
    }
}
