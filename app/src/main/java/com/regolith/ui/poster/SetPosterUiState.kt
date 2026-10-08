package com.regolith.ui.poster

import android.graphics.Bitmap
import com.regolith.domain.artwork.FolderPoster
import com.regolith.domain.artwork.WholePoster
import com.regolith.ui.components.PosterSwap

/**
 * Everything Set as poster draws, as one value (the screen's single source
 * of truth). As in the poster editor, the crop box's framing is the
 * screen's own, not here: it changes on every pixel of a pinch.
 */
data class SetPosterUiState(
    /** The folder whose poster this becomes: "Lake house 2024". */
    val folderName: String = "",
    /** The picture's title on the share ("IMG_4821"), or null for one from the phone. */
    val pictureTitle: String? = null,
    /** The picture's file name on the share, for what it would be renamed to. */
    val pictureName: String? = null,
    /** The picture as decoded for framing, or null before it arrives. */
    val picture: Bitmap? = null,
    val loading: Boolean = true,
    /** The picture could not be fetched or read. */
    val unreadable: Boolean = false,
    /** Why nothing can be written here (spoof mode, a folder with no share behind it), or null. */
    val readOnly: String? = null,
    /** "Use whole picture" rather than the 2:3 crop. */
    val whole: Boolean = false,
    /** How a picture on the share goes whole ([FolderPoster.whole]); null for one from the phone. */
    val wholeHow: WholePoster? = null,
    /** A GIF from the phone that goes up whole as `poster.gif` and moves on the Library. */
    val moves: Boolean = false,
    val saving: Boolean = false,
    /** The folder has a poster already: the sheet that asks first ([PosterSwap]). */
    val swap: PosterSwap? = null,
    /** Why the last try did not go, in words for the user. */
    val error: String? = null,
    /** Set: the screen closes itself, and the app's message line says so. */
    val done: Boolean = false,
) {
    /** "Lake house 2024 · from IMG_4821", under the screen's title. */
    val subtitle: String
        get() = listOf(folderName, pictureTitle?.let { "from $it" } ?: "from your phone").filter { it.isNotEmpty() }.joinToString(" · ")

    /** Already the folder's poster, used whole: there is nothing to do. */
    val alreadyPoster: Boolean get() = whole && wholeHow == WholePoster.ALREADY

    val canSave: Boolean get() = picture != null && !saving && readOnly == null && !alreadyPoster

    /** The line over the choice: what the choice made will do. */
    val hint: String
        get() = when {
            !whole && moves -> "Pinch and drag the picture to frame it. A cropped GIF is a still poster"
            !whole -> "Pinch and drag the picture to frame it"
            wholeHow == WholePoster.ALREADY -> "This is $folderName’s poster already"
            wholeHow == WholePoster.RENAME -> "${pictureName.orEmpty()} is renamed ${FolderPoster.renamedTo(pictureName.orEmpty())}, as it is"
            wholeHow == WholePoster.COPY -> "Saved as ${FolderPoster.NAME}, a copy sized for a poster; ${pictureName.orEmpty()} stays"
            moves -> "Saved as poster.gif: it moves on the Library"
            else -> "Saved as ${FolderPoster.NAME}, the whole picture"
        }
}
