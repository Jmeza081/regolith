package com.regolith.ui.player

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withStateAtLeast
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

/*
 * The mini player: the film carrying on, small, once the player has been put
 * away (back, the swipe down, the arrow). The canvas "Next Five Features",
 * boards MP-Flow and MP-Inner. A phone gets a bar directly above the nav
 * pill; a wide window, where the nav is a rail on the start edge, gets a card
 * in the bottom corner away from it. Either way the picture is the film
 * itself, still playing, and a tap brings the player back up.
 *
 * Its own surface is a TextureView, unlike the player's SurfaceView: a
 * TextureView is drawn through the view hierarchy, so it takes the rounded
 * corners and the fades a SurfaceView ignores. One ExoPlayer feeds one
 * surface at a time, and whichever was attached last gets the picture.
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
 */
@Stable
class MiniPlayerHandoff {
    var slot: Rect? by mutableStateOf(null)
    var playerShowing by mutableStateOf(false)
}

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
                    .aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)),
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
 * What happens when a film ends while the player is small. A queue, a
 * repeat, or Settings › Playback › Keep playing carries on to the next film
 * at once, without the player's ten-second card — there is nobody looking at
 * a countdown in a bar. With nothing to follow it, the mini player closes,
 * as the player does when it is left on a finished film: a bar holding a
 * film that is over is only in the way. Either way only while the app is in
 * front, as in the player: a film that ends behind another app waits.
 */
@Composable
fun MiniPlayerAtTheEnd(state: PlaybackState, autoplayNext: Boolean, onPlay: (fileId: Long) -> Unit, onClose: () -> Unit) {
    val next = state.playsOnTo(autoplayNext)
    val ended = state.ended && state.playWhenReady
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(ended, next?.fileId) {
        if (!ended) return@LaunchedEffect
        lifecycle.withStateAtLeast(Lifecycle.State.RESUMED) {
            if (next != null) onPlay(next.fileId) else onClose()
        }
    }
}

/**
 * The film itself, cropped to the box: a TextureView, so the corners and
 * fades apply. Its own thumbnail sits behind it, for the frame or two a new
 * surface takes to be given a picture, which would otherwise be black.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun MiniPicture(player: Player?, fileId: Long?, modifier: Modifier) {
    Box(modifier.background(Color.Black).testTag("mini_player_picture")) {
        if (fileId != null) ArtworkImage(ArtworkRequest(ArtworkOwner.File(fileId), ArtworkKind.THUMB), Modifier.fillMaxSize())
        if (player != null) ContentFrame(player, Modifier.fillMaxSize(), SURFACE_TYPE_TEXTURE_VIEW, ContentScale.Crop)
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
