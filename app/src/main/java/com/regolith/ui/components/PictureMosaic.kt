package com.regolith.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.regolith.R
import com.composables.icons.lucide.R as LucideR
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles

/*
 * An album's mosaic (the canvas "Images on the Share", Col-Images): pictures
 * stacked down columns of one width, each at its own shape, the way a
 * photo gallery lays out a folder. The columns themselves are a Compose
 * `LazyVerticalStaggeredGrid` (CSS's masonry layout, as a lazy list), laid
 * out by the screen that shows them; this is one picture in it.
 */

/** A mosaic tile's corners: smaller than a poster's 12dp, so a wall of photos reads as photos. */
val PictureTileShape: Shape = RoundedCornerShape(6.dp)

/** The gap between a mosaic's columns, and between the pictures down each one. */
val PictureMosaicGap: Dp = 4.dp

/**
 * One picture in an album's mosaic, at its own [aspect] (width over
 * height). Over the picture, as a poster tile carries its facts:
 * - "POSTER" top-left when it is the collection's own poster ([poster]):
 *   editing it changes the poster;
 * - "GIF" top-right for a picture that moves (it is still here, and moves
 *   in the lightbox);
 * - the video it belongs to along the bottom ([videoName]), for a video's
 *   own picture (`beach.jpg` beside `beach.mp4`).
 *
 * [checked] is selection, as on [MediaTile]: null when not selecting. [owner]
 * is what the picture flies as into the lightbox ([posterFlight]).
 */
@Composable
fun PictureMosaicTile(
    artwork: ArtworkRequest?,
    aspect: Float,
    name: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    poster: Boolean = false,
    gif: Boolean = false,
    videoName: String? = null,
    onLongClick: (() -> Unit)? = null,
    checked: Boolean? = null,
    owner: ArtworkOwner? = artwork?.owner,
) {
    val colors = RegolithTheme.colors
    val picked = checked == true
    Box(
        modifier
            .fillMaxWidth()
            // A picture this flat or this tall is held to a band, so one
            // panorama does not become a sliver or one screenshot a tower.
            .aspectRatio(aspect.coerceIn(MIN_ASPECT, MAX_ASPECT))
            .then(if (picked) Modifier.border(2.dp, colors.ink, PictureTileShape).padding(3.dp) else Modifier)
            .posterFlight(owner)
            .clip(PictureTileShape)
            .background(colors.surface)
            .combinedClickable(interactionSource = null, indication = null, onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = name }
            .testTag(testTag),
    ) {
        ArtworkImage(artwork, Modifier.fillMaxSize(), fallbackLabel = name)
        if (poster) {
            MosaicBadge(
                "Poster", Modifier.align(Alignment.TopStart).padding(Spacing.s4),
                icon = LucideR.drawable.lucide_ic_rectangle_vertical,
            )
        }
        if (checked != null) {
            MosaicPick(picked, Modifier.align(Alignment.TopEnd).padding(Spacing.s4))
        } else if (gif) {
            MosaicBadge("GIF", Modifier.align(Alignment.TopEnd).padding(Spacing.s4))
        }
        if (videoName != null) {
            MosaicBadge(
                videoName,
                Modifier.align(Alignment.BottomStart).padding(Spacing.s4).widthIn(max = 160.dp),
                icon = LucideR.drawable.lucide_ic_square_play,
                caps = false,
            )
        }
    }
}

/** A fact over a mosaic picture: black 72% pill, 600 9px, tracked capitals unless it is a name. */
@Composable
private fun MosaicBadge(text: String, modifier: Modifier = Modifier, icon: Int? = null, caps: Boolean = true) {
    val colors = RegolithTheme.colors
    Row(
        modifier.background(colors.overArt, PillShape).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s4),
    ) {
        if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = colors.ink, modifier = Modifier.size(10.dp))
        Text(
            if (caps) text.uppercase() else text,
            style = if (caps) TextStyles.tag else TextStyles.badge,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The pick mark on a mosaic picture while selecting: a filled ink circle with a check, a hollow ring when not. */
@Composable
private fun MosaicPick(picked: Boolean, modifier: Modifier = Modifier) {
    val colors = RegolithTheme.colors
    Box(
        modifier
            .size(20.dp)
            .background(if (picked) colors.ink else colors.overArt, PillShape)
            .then(if (picked) Modifier else Modifier.border(1.5.dp, colors.onMediaCircleBorder, PillShape)),
        contentAlignment = Alignment.Center,
    ) {
        if (picked) Icon(painterResource(R.drawable.rg_ic_check), contentDescription = "Picked", tint = colors.ground, modifier = Modifier.size(12.dp))
    }
}

/** How wide or tall a mosaic tile may be drawn: 3:1 at the widest, 1:3 at the tallest. */
private const val MIN_ASPECT = 1f / 3f
private const val MAX_ASPECT = 3f

/**
 * How many mosaic columns fit across [width] at all, none narrower than
 * [PICTURE_TILE_MIN]: the most a pinch can reach before its own range stops it.
 */
fun picturesFitting(width: Dp): Int = ((width + PictureMosaicGap) / (PICTURE_TILE_MIN + PictureMosaicGap)).toInt().coerceAtLeast(1)

/** The narrowest a mosaic column gets: four across a 411dp phone are about 90dp. */
val PICTURE_TILE_MIN: Dp = 72.dp
