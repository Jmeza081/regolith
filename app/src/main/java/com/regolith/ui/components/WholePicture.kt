package com.regolith.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import com.regolith.data.pictures.PictureOriginal
import com.regolith.data.spoof.Spoof

/**
 * A picture on the share at its own full size, for the lightbox and a story:
 * the file fetched whole ([PictureOriginal]), or spoof mode's stand-in for
 * it. [target] is the longest side to decode at, in pixels; [version] is the
 * picture's size and date on the share, so one replaced under the same name
 * is fetched again (the `?v=` of a URL).
 *
 * [onState] hears how the load went: a story starts a picture's time only
 * once it is on screen, or has failed and its thumbnail must do.
 */
@Composable
fun WholePicture(
    pictureId: Long,
    version: String,
    target: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    onState: ((AsyncImagePainter.State) -> Unit)? = null,
) {
    val context = LocalContext.current
    val spoof = LocalSpoof.current
    val request = remember(pictureId, version, target, spoof) { wholePictureRequest(context, spoof, pictureId, version, target) }
    AsyncImage(model = request, contentDescription = contentDescription, modifier = modifier, contentScale = contentScale, onState = onState)
}

/**
 * The request [WholePicture] makes, for warming the image loader with a
 * picture before it shows: the same request, so the same memory-cache entry.
 */
fun wholePictureRequest(context: Context, spoof: Spoof?, pictureId: Long, version: String, target: Int): ImageRequest =
    ImageRequest.Builder(context)
        .data(spoof?.picture(pictureId) ?: PictureOriginal(pictureId, version))
        .size(target, target)
        .precision(Precision.INEXACT)
        .crossfade(true)
        .build()
