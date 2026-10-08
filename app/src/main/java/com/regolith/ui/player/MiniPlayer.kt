package com.regolith.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.composables.icons.lucide.R as LucideR
import com.regolith.R
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.player.PlaybackState
import com.regolith.ui.components.ArtworkImage
import com.regolith.ui.components.IconCircleButton
import com.regolith.ui.components.navChromeFrost
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.util.formatClock
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/*
 * The mini player: the film carrying on, small, once the player has been put
 * away (back, the swipe down, the arrow). The canvas "Next Five Features",
 * boards MP-Flow and MP-Inner. A phone gets a bar directly above the nav
 * pill; a wide window, where the nav is a rail on the start edge, gets a card
 * in the bottom corner away from it. Either way the picture is the film
 * itself, still playing, and a tap brings the player back up.
 *
 * Its own surface is a TextureView, as the player's is while it is not full
 * screen: a TextureView is drawn through the view hierarchy, so it takes the
 * rounded corners and the fades a SurfaceView ignores, and it can be read
 * back. One ExoPlayer feeds one surface at a time, and whichever was attached
 * last gets the picture.
 */

/** How tall the phone's bar is; what a tab screen adds to its bottom clearance while it shows. */
val MINI_BAR_HEIGHT: Dp = 57.dp

/** The bar's picture: inset this far from the bar's start, top and bottom, 16:9 in what is left. */
private val MINI_BAR_PICTURE_INSET: Dp = 6.dp

/** How wide the wide window's card is, and how tall: a 16:9 picture over one row. */
val MINI_CARD_WIDTH: Dp = 300.dp
val MINI_CARD_HEIGHT: Dp = MINI_CARD_WIDTH * 9 / 16 + 56.dp

/**
 * What the player and the mini player tell each other as the film passes
 * between them: where the mini player's picture will be once the player is
 * put away ([slot], in the window, worked out by NavGraph from the same
 * sizes the bar and the card are laid out with), and whether the player is
 * still on screen ([playerShowing]) — which includes the moments it spends
 * shrinking into that slot, during which the mini player waits rather than
 * taking the picture off it. One ExoPlayer can draw on one surface at a time.
 *
 * Each also says where its picture is right now ([playerPicture],
 * [miniPicture]; null while it is not on screen), for the picture-in-picture
 * window to grow out of when the app is left.
 *
 * [frame] is the film as the side letting go of it last showed it, read off
 * that side's surface (videoFrame): a surface taking the film over has
 * nothing on it for a frame or several, and that was a black box behind the
 * picture as it landed. The side taking over shows the frame until its own
 * surface has drawn one. [landed] is the same frame for the moment after the
 * picture lands in the mini player: held over it (LandedPicture) while the
 * rest of the mini player fades in around the picture.
 *
 * Both show the film whole, fitted to the box, as the player does unless it
 * was pinched to fill: an upright phone video stands in the middle of the box
 * on the mini player's own ground. Cropped to fill the box, the narrow
 * picture that landed jumped to a wide one, and the box filled in black
 * behind it first.
 */
@Stable
class MiniPlayerHandoff {
    var slot: Rect? by mutableStateOf(null)
    var playerShowing by mutableStateOf(false)
    var playerPicture: Rect? by mutableStateOf(null)
    var miniPicture: Rect? by mutableStateOf(null)
    var frame: ImageBitmap? by mutableStateOf(null)
    var landed: LandedFrame? by mutableStateOf(null)
}

/** A frame the picture landed in the mini player with, and how it sat in the player's box: fitted, or filling it. */
@Immutable
class LandedFrame(val frame: ImageBitmap, val contentScale: ContentScale)

val LocalMiniPlayerHandoff = staticCompositionLocalOf<MiniPlayerHandoff?> { null }

/**
 * Where a phone's bar puts its picture, in the window: [lift] is how far the
 * bar sits above its resting place (the pill's height and a gap when the pill
 * shows under it). [bottomInset] is the navigation bar's.
 */
fun miniBarPictureRect(window: Size, bottomInset: Dp, lift: Dp, density: Density): Rect = with(density) {
    val height = MINI_BAR_HEIGHT - MINI_BAR_PICTURE_INSET * 2
    val width = height * 16 / 9
    val barTop = window.height - (bottomInset + Spacing.s8 + lift + MINI_BAR_HEIGHT).toPx()
    val left = (Spacing.s18 + MINI_BAR_PICTURE_INSET).toPx()
    val top = barTop + MINI_BAR_PICTURE_INSET.toPx()
    Rect(left, top, left + width.toPx(), top + height.toPx())
}

/**
 * Where a wide window's card puts its picture, in the window: across the
 * card's top, the card [end] in from the window's end edge.
 */
fun miniCardPictureRect(window: Size, bottomInset: Dp, end: Dp, density: Density): Rect = with(density) {
    val right = window.width - end.toPx()
    val top = window.height - (bottomInset + Spacing.s18 + MINI_CARD_HEIGHT).toPx()
    Rect(right - MINI_CARD_WIDTH.toPx(), top, right, top + (MINI_CARD_WIDTH * 9 / 16).toPx())
}

/**
 * The frame the picture has just landed in the mini player with, over
 * everything at [slot] (in the window) and in the shape the mini player's
 * picture has there ([wide]: the card's, else the bar's). It holds the picture
 * still and whole for [fadeMs] while the rest of the mini player fades in
 * around it, the way YouTube's does, then fades out over the mini player's
 * own picture — the same frame, until its surface has drawn one. Fading
 * rather than going covers a player that was pinched to fill its box, where
 * the two are framed differently. [onDone] is called once it has faded, or
 * once it has gone from the screen before that, so it is never shown twice.
 */
@Composable
fun LandedPicture(landed: LandedFrame, slot: Rect, wide: Boolean, fadeMs: Int, onDone: () -> Unit) {
    val density = LocalDensity.current
    val alpha = remember(landed) { Animatable(1f) }
    LaunchedEffect(landed) {
        try {
            delay(fadeMs.toLong())
            alpha.animateTo(0f, tween(fadeMs))
        } finally {
            onDone()
        }
    }
    Image(
        landed.frame,
        contentDescription = null,
        contentScale = landed.contentScale,
        modifier = Modifier
            .offset { IntOffset(slot.left.roundToInt(), slot.top.roundToInt()) }
            .size(with(density) { slot.width.toDp() }, with(density) { slot.height.toDp() })
            .graphicsLayer { this.alpha = alpha.value }
            .clip(if (wide) MiniCardPictureShape else MiniBarPictureShape),
    )
}

/**
 * The phone's mini player: the picture at the start of a frosted bar, the
 * title over where you are and where it lives, play/pause, close, and a red
 * line of progress along the foot. A tap anywhere else opens the player.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun MiniPlayerBar(
    state: PlaybackState,
    player: Player?,
    hazeState: HazeState,
    onExpand: () -> Unit,
    onTogglePlay: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RegolithTheme.colors
    val smooth = rememberSmoothProgress(player)
    Box(
        modifier
            .fillMaxWidth()
            .height(MINI_BAR_HEIGHT)
            .navChromeFrost(hazeState, MiniShape)
            .clickable(interactionSource = null, indication = null, onClickLabel = "Open the player", role = Role.Button, onClick = onExpand)
            .testTag("mini_player_bar"),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            MiniPicture(
                player,
                state.fileId,
                Modifier.padding(start = MINI_BAR_PICTURE_INSET).height(MINI_BAR_HEIGHT - MINI_BAR_PICTURE_INSET * 2)
                    .aspectRatio(16f / 9f).clip(MiniBarPictureShape),
            )
            Spacer(Modifier.width(Spacing.s12))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(state.title, style = TextStyles.miniPlayerTitle, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Where you are, and where the film lives: the time first, as the
                // one thing that changes.
                PositionClock(
                    ms = smooth::clockMs,
                    style = TextStyles.tileMeta,
                    color = colors.metadata,
                    suffix = miniPlace(state)?.let { " · $it" }.orEmpty(),
                    modifier = Modifier.testTag("mini_player_position"),
                )
            }
            MiniPlayPause(state, onTogglePlay)
            MiniButton(R.drawable.rg_ic_close, "Close the player", colors.navIdle, "mini_player_close", onClose)
            Spacer(Modifier.width(4.dp))
        }
        MiniProgress(
            smooth,
            Modifier.align(Alignment.BottomCenter).padding(start = 14.dp, end = 14.dp, bottom = 3.dp).fillMaxWidth().height(2.dp),
        )
    }
}

/**
 * The wide window's mini player: a card in the bottom corner, the picture
 * across its top with the button that opens the player over it, and the
 * title, the time and the transport underneath.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun MiniPlayerCard(
    state: PlaybackState,
    player: Player?,
    hazeState: HazeState,
    onExpand: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RegolithTheme.colors
    val smooth = rememberSmoothProgress(player)
    Column(
        modifier
            .width(MINI_CARD_WIDTH)
            .navChromeFrost(hazeState, MiniShape)
            .testTag("mini_player_card"),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clickable(interactionSource = null, indication = null, onClickLabel = "Open the player", role = Role.Button, onClick = onExpand),
        ) {
            MiniPicture(player, state.fileId, Modifier.fillMaxSize())
            IconCircleButton(
                icon = painterResource(LucideR.drawable.lucide_ic_maximize_2),
                contentDescription = "Open the player",
                onClick = onExpand,
                onMedia = true,
                size = 36.dp,
                iconSize = 14.dp,
                testTag = "mini_player_expand",
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
            )
            MiniProgress(smooth, Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp), rounded = false)
        }
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(state.title, style = TextStyles.miniPlayerTitle, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                PositionClock(
                    ms = smooth::clockMs,
                    style = TextStyles.tileMeta,
                    color = colors.metadata,
                    suffix = state.durationMs.takeIf { it > 0 }?.let { " of ${formatClock(it)}" }.orEmpty(),
                    modifier = Modifier.testTag("mini_player_position"),
                )
            }
            MiniButton(R.drawable.rg_ic_skip_previous, "Previous", colors.ink, "mini_player_previous", onPrevious)
            MiniPlayPause(state, onTogglePlay)
            MiniButton(R.drawable.rg_ic_skip_next, "Next", colors.ink, "mini_player_next", onNext)
            MiniButton(R.drawable.rg_ic_close, "Close the player", colors.navIdle, "mini_player_close", onClose)
        }
    }
}

/**
 * The film itself, whole in the box, as the player shows it (MiniPlayerHandoff
 * says why it is not cropped): a TextureView, so the corners and fades apply.
 * Behind it, for the frame or several a new surface takes to be given a
 * picture: the frame the player handed over with the film, if it just did
 * (MiniPlayerHandoff.frame), else the film's thumbnail. An empty TextureView
 * draws nothing, so what is behind it shows until it has a frame. Wherever
 * the film does not reach, the mini player's own ground shows.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun MiniPicture(player: Player?, fileId: Long?, modifier: Modifier) {
    val handoff = LocalMiniPlayerHandoff.current
    DisposableEffect(handoff) { onDispose { handoff?.miniPicture = null } }
    val drawing = player != null && !LocalInPictureInPicture.current
    // Its surface's first frame: the player renders to one surface at a time,
    // and tells of the first frame on each new one. What stands in behind an
    // empty surface goes then: the thumb, a 16:9 picture that would show
    // either side of a narrower film; and the frame handed over, so that a
    // surface made again later (back from picture-in-picture, back from
    // Shorts) has the thumb behind it, not a frame from however long ago.
    var drawn by remember(player, drawing) { mutableStateOf(false) }
    DisposableEffect(player, drawing, handoff) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                drawn = true
                handoff?.frame = null
            }
        }
        if (drawing) player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    Box(
        modifier
            .onGloballyPositioned { handoff?.miniPicture = it.boundsInRoot() }
            .testTag("mini_player_picture"),
    ) {
        val handed = handoff?.frame
        if (handed != null) {
            Image(handed, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else if (fileId != null && !drawn) {
            ArtworkImage(ArtworkRequest(ArtworkOwner.File(fileId), ArtworkKind.THUMB), Modifier.fillMaxSize())
        }
        // Floating, the picture-in-picture window has the film; this takes it back after.
        // No shutter: Media3's is a black box until the first frame, over what is behind.
        if (drawing && player != null) {
            ContentFrame(player, Modifier.fillMaxSize(), SURFACE_TYPE_TEXTURE_VIEW, ContentScale.Fit, shutter = {})
        }
    }
}

/** The red line of how far along: drawn, so a tick repaints the line and nothing around it. */
@Composable
private fun MiniProgress(smooth: SmoothProgress, modifier: Modifier, rounded: Boolean = true) {
    val colors = RegolithTheme.colors
    Box(
        modifier
            .onSizeChanged { smooth.onTrackWidth(it.width) }
            .drawBehind {
                val radius = if (rounded) CornerRadius(size.height / 2) else CornerRadius.Zero
                drawRoundRect(Color.White.copy(alpha = 0.22f), cornerRadius = radius)
                drawRoundRect(colors.accent, size = Size(size.width * smooth.fraction(), size.height), cornerRadius = radius)
            },
    )
}

/**
 * Pause while it plays; Play while it is paused, and once it has ended, when
 * a tap plays it again from the start (as the session's toggle does).
 */
@Composable
private fun MiniPlayPause(state: PlaybackState, onTogglePlay: () -> Unit) {
    val playing = state.playWhenReady && !state.ended
    MiniButton(
        if (playing) R.drawable.rg_ic_pause else R.drawable.rg_ic_play,
        if (playing) "Pause" else "Play",
        RegolithTheme.colors.ink,
        "mini_player_play",
        onTogglePlay,
    )
}

/** A 40×44 glyph button, no fill: the bar's and the card's controls. Null greys it out. */
@Composable
private fun MiniButton(icon: Int, label: String, tint: Color, testTag: String, onClick: (() -> Unit)?) {
    Box(
        Modifier
            .size(width = 40.dp, height = 44.dp)
            .clickable(enabled = onClick != null, interactionSource = null, indication = null, onClickLabel = label, role = Role.Button) { onClick?.invoke() }
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = label, tint = if (onClick != null) tint else tint.copy(alpha = 0.3f), modifier = Modifier.size(18.dp))
    }
}

/**
 * Where the film lives, in a word: the folder it is in ("Lake house 2024"),
 * out of the session's "share · path". A film another app handed over says
 * that instead.
 */
private fun miniPlace(state: PlaybackState): String? =
    state.sourceLabel.substringAfterLast(" · ").substringAfterLast('/').ifEmpty { null }

/** The bar's and the card's corners, as the canvas drew them. */
private val MiniShape = RoundedCornerShape(18.dp)

/** The card's picture: the card's own corners along its top, square where it meets the row under it. */
private val MiniCardPictureShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)

/** The bar's picture, rounded on its own inside the bar. */
private val MiniBarPictureShape = RoundedCornerShape(12.dp)
