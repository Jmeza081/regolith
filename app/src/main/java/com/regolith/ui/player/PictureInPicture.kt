package com.regolith.ui.player

import android.app.PictureInPictureParams
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toAndroidRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import com.regolith.domain.playback.VideoInfo

/*
 * Picture-in-picture: leave Regolith while a film plays and Android shrinks
 * the whole app into a small window over the other apps (Settings ›
 * Playback › Picture-in-picture). It is still this one Activity, drawing in a
 * window the size of a playing card, so while it floats the app draws the
 * film and nothing else ([PictureInPictureFilm]); the window's buttons come
 * from the media session (PlaybackService). Web analogy: the browser's own
 * picture-in-picture for a <video>, except the "video element" is the page.
 */

/**
 * True while the app is in its picture-in-picture window. The player and the
 * mini player let go of the film then ([PictureInPictureFilm] has it — one
 * ExoPlayer draws on one surface) and take it back when the window opens out.
 */
val LocalInPictureInPicture = staticCompositionLocalOf { false }

/** Whether [activity] is in picture-in-picture, following it as it goes in and out. */
@Composable
fun rememberInPictureInPicture(activity: ComponentActivity?): Boolean {
    if (activity == null) return false
    var floating by remember(activity) { mutableStateOf(activity.isInPictureInPictureMode) }
    DisposableEffect(activity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { floating = it.isInPictureInPictureMode }
        activity.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return floating
}

/**
 * The picture-in-picture window's whole content: the film, fitted, on black.
 * A SurfaceView, the cheapest way to put video on screen, since nothing here
 * needs rounding or fading.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PictureInPictureFilm(player: Player?, modifier: Modifier = Modifier) {
    Box(modifier.background(Color.Black).testTag("pip_film")) {
        if (player != null) ContentFrame(player, Modifier, SURFACE_TYPE_SURFACE_VIEW, ContentScale.Fit)
    }
}

/**
 * What the Activity tells Android about its picture-in-picture window: enter
 * it by itself when the app is left ([floats]: a film is playing and the
 * setting is on), shaped like the film, growing out of [source] — where the
 * film is on screen, the player's picture or the mini player's. Without
 * that, the whole app shrinks into the window instead. Seamless resizing is
 * off, as Android advises for video: on, the window cross-fades as it
 * changes size.
 */
fun pictureInPictureParams(floats: Boolean, video: VideoInfo?, source: Rect?): PictureInPictureParams =
    PictureInPictureParams.Builder()
        .setAutoEnterEnabled(floats)
        .setSeamlessResizeEnabled(false)
        .apply { pipAspect(video)?.let { (w, h) -> setAspectRatio(Rational(w, h)) } }
        .apply { source?.takeIf { it.width > 0f && it.height > 0f }?.let { setSourceRectHint(it.toAndroidRect()) } }
        .build()

/**
 * The film's shape for its window, held inside what Android allows a
 * picture-in-picture window to be (no further from square than 2.39:1 either
 * way). Null while the film's size is not known yet, which leaves the
 * window at Android's own shape.
 */
fun pipAspect(video: VideoInfo?): Pair<Int, Int>? {
    if (video == null || video.width <= 0 || video.height <= 0) return null
    val ratio = video.width.toFloat() / video.height
    return when {
        ratio > MAX_PIP_RATIO -> 239 to 100
        ratio < 1f / MAX_PIP_RATIO -> 100 to 239
        else -> video.width to video.height
    }
}

/** How far from square Android lets a picture-in-picture window be. */
private const val MAX_PIP_RATIO = 2.39f
