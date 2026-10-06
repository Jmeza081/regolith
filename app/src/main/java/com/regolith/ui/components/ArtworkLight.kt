package com.regolith.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import com.regolith.domain.artwork.ArtworkRequest

/**
 * Artwork shone as light instead of shown as a picture: scaled past its own
 * edges so nothing has a border, blurred, faded, and pushed past its own
 * saturation so the wash reads as coloured light rather than haze.
 *
 * It is the recipe the player's Mirror light has always used behind a film,
 * lifted out so a collection's profile can light the top of its page with
 * its poster the same way. Every caller lays its own scrim over it, because
 * how the light falls off is the caller's business: down to the page's
 * ground under a profile, evenly across a letterbox bar in the player.
 *
 * [overlay] draws inside the same blur, so a second picture mixes with the
 * artwork as light instead of sitting on it as a picture. It is handed the
 * saturation filter so both layers are pushed alike; the player puts its
 * live frame sample there.
 *
 * Web analogy: an `<img>` behind the content with
 * `transform: scale(1.35); filter: blur(32px) saturate(1.45); opacity: .65`.
 *
 * Nothing shows until the picture has loaded, and nothing replaces it if it
 * never does: an unlit page is the fallback, never a grey box.
 */
@Composable
fun ArtworkLight(
    artwork: ArtworkRequest,
    modifier: Modifier = Modifier,
    testTag: String = "artwork_light",
    overlay: @Composable BoxScope.(lift: ColorFilter) -> Unit = {},
) {
    // Film and posters are graded for a screen you look at, not for a lamp.
    // Pushed a little past life they read as coloured light; left alone the
    // blur averages most pictures into grey.
    val lift = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(LIGHT_SATURATION) }) }
    Box(modifier.clipToBounds()) {
        // One blur over every layer rather than one each, so the layers mix
        // as light rather than as pictures stacked on top of each other.
        Box(
            Modifier.fillMaxSize()
                .scale(LIGHT_SCALE)
                .blur(LIGHT_BLUR, BlurredEdgeTreatment.Unbounded)
                .alpha(LIGHT_ALPHA),
        ) {
            SubcomposeAsyncImage(
                // Spoof mode lights the page with the stand-in photo instead.
                model = LocalSpoof.current?.image(artwork) ?: artwork,
                contentDescription = null,
                loading = {},
                error = {},
                success = { SubcomposeAsyncImageContent(contentScale = ContentScale.Crop, colorFilter = lift) },
                modifier = Modifier.fillMaxSize().testTag(testTag),
            )
            overlay(lift)
        }
    }
}

/**
 * Takes the last edges off. Small for a blur, because the radius is what it
 * costs: the player's 32-pixel frame sample blown up to a phone is already
 * smooth, and a poster stretched across a header nearly is.
 */
private val LIGHT_BLUR: Dp = 32.dp

/** Scaled past the edges so the blur has nothing to fade into. */
private const val LIGHT_SCALE = 1.35f

private const val LIGHT_ALPHA = 0.65f

/** Past life, so the wash reads as light rather than as haze. 1f would be the picture's own grade. */
private const val LIGHT_SATURATION = 1.45f
