package com.regolith.ui.story

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImagePainter
import coil3.imageLoader
import com.regolith.R
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.domain.playback.Story
import com.regolith.ui.adaptive.LocalWindowShape
import com.regolith.ui.components.ArtworkImage
import com.regolith.ui.components.IconCircleButton
import com.regolith.ui.components.KeepScreenOn
import com.regolith.ui.components.LocalSpoof
import com.regolith.ui.components.PictureScrim
import com.regolith.ui.components.ScrimEdge
import com.regolith.ui.components.StorySegments
import com.regolith.ui.components.SwipeToClose
import com.regolith.ui.components.WholePicture
import com.regolith.ui.components.onPicture
import com.regolith.ui.components.rememberSwipeToClose
import com.regolith.ui.components.swipeToClose
import com.regolith.ui.components.wholePictureRequest
import com.regolith.ui.library.PictureTile
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * A collection's pictures played as a story (the canvas "Images on the
 * share", Story-Play and Story-Inner): one at a time on black, each for the
 * time Settings › Playback › Picture stories says, with a bar of segments
 * across the top and the album beside its poster under it.
 *
 * Tap the left third for the picture before, anywhere else for the next;
 * hold to pause while the finger is down — letting go carries on with the
 * same picture — or pause from the button; swipe down to close, which
 * drives the screen's own leaving as a back gesture, as the lightbox's does.
 * Past the last picture the story closes itself.
 *
 * A picture's time starts once it is on screen, so a slow share never eats
 * into it; the thumbnail shows at once while the picture itself comes. On
 * a phone a picture about the screen's own shape fills it; any other picture,
 * and every picture on the inner display, is shown whole over a blurred
 * copy of itself.
 */
@Composable
fun StoryScreen(
    viewModel: StoryViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.finished) { if (state.finished) onClose() }
    Box(modifier.fillMaxSize().background(Color.Black).testTag("story_screen")) {
        KeepScreenOn()
        if (!state.loaded) return@Box
        if (state.pictures.isEmpty()) {
            // Nothing to play (every picture went meanwhile).
            LaunchedEffect(Unit) { onClose() }
            return@Box
        }
        Story(state, onNext = viewModel::next, onPrevious = viewModel::previous, onPause = viewModel::togglePause, onClose = onClose)
    }
}

@Composable
private fun Story(state: StoryUiState, onNext: () -> Unit, onPrevious: () -> Unit, onPause: () -> Unit, onClose: () -> Unit) {
    val picture = state.current ?: return
    val wide = LocalWindowShape.current.wide
    val density = LocalDensity.current
    val context = LocalContext.current
    val spoof = LocalSpoof.current

    // The segment filling, started over by every move (state.step).
    val progress = remember(state.step) { Animatable(0f) }
    // The picture is on screen, or failed and its thumbnail stands in.
    var ready by remember(picture.pictureId) { mutableStateOf(false) }
    // A finger held on the picture, or a swipe down under way: either stops the clock.
    var pressed by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    val held = pressed || dragging

    // A picture that will not come still has its turn, on its thumbnail.
    LaunchedEffect(picture.pictureId) {
        delay(READY_WAIT_MS)
        ready = true
    }
    // The clock: what is left of this picture's time, from where the bar
    // stands, so a pause and a GIF's length arriving late both carry on
    // from there rather than starting over.
    LaunchedEffect(state.step, state.paused, held, ready, state.durationMs) {
        if (!ready || state.paused || held) return@LaunchedEffect
        val left = ((1f - progress.value) * state.durationMs).toInt().coerceAtLeast(0)
        progress.animateTo(1f, tween(left, easing = LinearEasing))
        onNext()
    }

    // Swipe down to close: the leaving transition, driven by the finger
    // ([SwipeToClose], the lightbox's way).
    val swipe = rememberSwipeToClose()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val travelPx = with(density) { maxHeight.toPx() } * PULL_TRAVEL
        val flickPxPerS = with(density) { SwipeToClose.FLICK.toPx() }
        SideEffect {
            swipe?.travelPx = travelPx
            swipe?.flickPxPerS = flickPxPerS
        }
        // Decoded at the screen's own size: a story does not zoom.
        val target = with(density) { maxOf(maxWidth, maxHeight).roundToPx() }.coerceAtMost(MAX_DECODE)
        val fills = !wide && fillsScreen(picture.aspect, maxWidth / maxHeight)

        // The next picture decoded while this one shows, so it is there the
        // moment it is wanted (the same request, so the same cache entry).
        val next = state.pictures.getOrNull(state.index + 1)
        LaunchedEffect(next?.pictureId, target) {
            next?.let { context.imageLoader.enqueue(wholePictureRequest(context, spoof, it.pictureId, it.version, target)) }
        }

        Box(
            Modifier
                .fillMaxSize()
                // A tap steps; a hold pauses, and letting go carries on with the same picture.
                .tapOrHold(onHold = { pressed = it }, onTap = { back -> if (back) onPrevious() else onNext() })
                // A swipe down pauses the story too, until it closes it or lets go.
                .swipeToClose(swipe, onStart = { dragging = true }, onEnd = { dragging = false })
                .graphicsLayer {
                    // The story follows the finger down, a little smaller as it goes.
                    val pulled = swipe?.pull ?: 0f
                    translationY = pulled
                    val scale = 1f - 0.12f * (swipe?.fraction ?: 0f)
                    scaleX = scale
                    scaleY = scale
                }
                .testTag("story_stage"),
        ) {
            if (!fills) Backdrop(picture)
            if (wide) {
                WideStory(state, picture, target, onReady = { ready = true }, progress = { progress.value }, onPause = onPause, onClose = onClose)
            } else {
                key(picture.pictureId) {
                    StoryPicture(picture, target, if (fills) ContentScale.Crop else ContentScale.Fit, Modifier.fillMaxSize()) { ready = true }
                }
                PictureScrim(ScrimEdge.TOP, Modifier.align(Alignment.TopCenter)) {
                    Column(Modifier.statusBarsPadding().padding(start = Spacing.s12, end = Spacing.s12, top = Spacing.s8)) {
                        Bar(state, progress = { progress.value })
                        Spacer(Modifier.height(Spacing.s12))
                        Header(state, onPause, onClose)
                    }
                }
            }
        }
    }
}

/**
 * The inner display (Story-Inner): the picture whole, centred under the bar
 * and the album. Those stay put at the top, at one width, so nothing jumps
 * as a tall picture follows a wide one.
 */
@Composable
private fun WideStory(
    state: StoryUiState,
    picture: PictureTile,
    target: Int,
    onReady: () -> Unit,
    progress: () -> Float,
    onPause: () -> Unit,
    onClose: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = WIDE_MARGIN, vertical = Spacing.s18)) {
        val headerWidth = minOf(maxWidth, WIDE_HEADER_MAX)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.width(headerWidth)) {
                Bar(state, progress)
                Spacer(Modifier.height(Spacing.s12))
                Header(state, onPause, onClose)
            }
            Spacer(Modifier.height(Spacing.s18))
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val (w, h) = fit(picture.aspect, maxWidth, maxHeight)
                key(picture.pictureId) {
                    StoryPicture(picture, target, ContentScale.Fit, Modifier.size(w, h).clip(RoundedCornerShape(8.dp)), onReady)
                }
            }
        }
    }
}

/** The picture: its thumbnail at once, and the picture itself over it as it arrives. */
@Composable
private fun StoryPicture(picture: PictureTile, target: Int, scale: ContentScale, modifier: Modifier, onReady: () -> Unit) {
    Box(modifier.testTag("story_picture_${picture.pictureId}")) {
        ArtworkImage(picture.artwork, Modifier.fillMaxSize(), fallbackLabel = picture.name, contentScale = scale)
        WholePicture(
            pictureId = picture.pictureId,
            version = picture.version,
            target = target,
            contentDescription = picture.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = scale,
            onState = { s -> if (s is AsyncImagePainter.State.Success || s is AsyncImagePainter.State.Error) onReady() },
        )
    }
}

/** Behind a picture that does not fill the screen: itself, blurred and dimmed, so the screen is never a black frame. */
@Composable
private fun Backdrop(picture: PictureTile) {
    ArtworkImage(
        picture.artwork,
        Modifier.fillMaxSize().blur(BACKDROP_BLUR).graphicsLayer { alpha = 0.7f },
        fallbackLabel = "",
        contentScale = ContentScale.Crop,
    )
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
}

/** The segments, the twenty around the picture up when there are more ([Story.window]). */
@Composable
private fun Bar(state: StoryUiState, progress: () -> Float) {
    StorySegments(
        count = state.pictures.size,
        index = state.index,
        progress = progress,
        description = "Picture ${state.index + 1} of ${state.pictures.size}",
        testTag = "story_segments",
    )
}

/** The album beside its poster, where the story is and when the picture was taken, then pause and close. */
@Composable
private fun Header(state: StoryUiState, onPause: () -> Unit, onClose: () -> Unit) {
    val colors = RegolithTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ArtworkImage(
            ArtworkRequest(ArtworkOwner.Folder(state.folderId), ArtworkKind.POSTER),
            Modifier.size(36.dp).clip(CircleShape).border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape),
            fallbackLabel = state.album,
        )
        Column(Modifier.padding(start = Spacing.s12).weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
            Text(
                state.album, style = TextStyles.rowLabel.onPicture(), color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("story_album"),
            )
            Text(
                state.position, style = TextStyles.meta.onPicture(), color = colors.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("story_position"),
            )
        }
        IconCircleButton(
            painterResource(if (state.paused) R.drawable.rg_ic_play else R.drawable.rg_ic_pause),
            if (state.paused) "Play" else "Pause",
            onPause, "story_pause_button",
            onMedia = true, size = 40.dp, iconSize = 16.dp,
        )
        Spacer(Modifier.width(Spacing.s8))
        IconCircleButton(
            painterResource(R.drawable.rg_ic_close), "Close", onClose, "story_close_button",
            onMedia = true, size = 40.dp, iconSize = 16.dp,
        )
    }
}

/**
 * Whether a picture of [aspect] (width over height) fills a phone screen of
 * [screen] cropped: when it is within a third of the screen's own shape,
 * the way a phone's stories fill with a portrait photo and float a wide one.
 */
private fun fillsScreen(aspect: Float, screen: Float): Boolean = abs(aspect / screen - 1f) < FILL_TOLERANCE

/** The picture's box fitted inside [width] × [height] at its [aspect]. */
private fun fit(aspect: Float, width: Dp, height: Dp): Pair<Dp, Dp> {
    val wideAspect = aspect >= width / height
    return if (wideAspect) width to width / aspect else height * aspect to height
}


/** How long a picture may take to arrive before its time starts anyway, on its thumbnail. */
private const val READY_WAIT_MS = 6_000L

/** How far a picture's shape may be from the screen's and still fill it cropped. */
private const val FILL_TOLERANCE = 0.34f

/** The biggest a picture is decoded, on its longer side. */
private const val MAX_DECODE = 4096

/** How far down the screen, as a fraction of its height, a pull carries the story all the way out. */
private const val PULL_TRAVEL = 0.5f

private val BACKDROP_BLUR = 48.dp

/** The inner display's margin around the story. */
private val WIDE_MARGIN = 48.dp

/** The bar and the album on the inner display: about the width of a tall picture there. */
private val WIDE_HEADER_MAX = 600.dp
