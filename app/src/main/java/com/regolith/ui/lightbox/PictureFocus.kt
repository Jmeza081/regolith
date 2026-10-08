package com.regolith.ui.lightbox

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Which picture the lightbox is showing, for the screen it closes back onto.
 *
 * The lightbox can be swiped a long way from the picture it opened on. Its
 * picture flies home into that picture's tile ([com.regolith.ui.components.posterFlight]),
 * and a flight needs its tile to be on screen: so the album brings the tile
 * into view as it reappears, by reading this. One per app, provided by the
 * nav graph, like the mini player's hand-off.
 */
@Stable
class PictureFocus {
    /** The picture on screen in the lightbox, or null when nothing has been opened. */
    var pictureId: Long? by mutableStateOf(null)
}

/** The app's [PictureFocus]; null outside the nav graph (previews, tests), where nothing flies. */
val LocalPictureFocus = staticCompositionLocalOf<PictureFocus?> { null }
