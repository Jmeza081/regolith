package com.regolith.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.lazy.grid.GridCells
import android.animation.ValueAnimator
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil3.BitmapImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import com.regolith.domain.artwork.ArtworkOwner
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import com.regolith.R
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.ui.theme.CardShape
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.TileShape
import com.regolith.ui.theme.scaledDp

/**
 * Art at any size: the image when there is one, the design's "reading"
 * look while it is pulled off the share, and, when there is no picture at
 * all, a dark gradient with the filename set inside. Used by
 * [MediaTile], [ResumeCard], list-row thumbnails and Title Detail.
 *
 * An [ArtworkRequest.animated] request plays a folder's moving poster
 * (a GIF), unless the phone's "Remove animations" is on: then it is the
 * still one, like everywhere else.
 */
@Composable
fun ArtworkImage(
    artwork: ArtworkRequest?,
    modifier: Modifier = Modifier,
    fallbackLabel: String = "",
    contentScale: ContentScale = ContentScale.Crop,
) {
    if (artwork == null) {
        NoPictureArt(fallbackLabel, modifier)
        return
    }
    // Counted while it is on its way, when a layout above is waiting to
    // appear with its pictures drawn ([LocalPendingArtwork]).
    val pending = LocalPendingArtwork.current
    val mark = remember { PendingMark() }
    if (pending != null) DisposableEffect(pending) { onDispose { mark.done(pending) } }
    // Settings › Accessibility › Remove animations turns off every animator
    // in the app; a moving poster is one more thing it should hold still.
    val model = if (artwork.animated && !ValueAnimator.areAnimatorsEnabled()) artwork.copy(animated = false) else artwork
    SubcomposeAsyncImage(
        model = model,
        contentDescription = null,
        modifier = modifier,
        loading = { ReadingArt() },
        error = { NoPictureArt(fallbackLabel, Modifier.fillMaxSize()) },
        success = { SubcomposeAsyncImageContent(contentScale = contentScale) },
        onLoading = pending?.let { p -> { _ -> mark.start(p) } },
        onSuccess = pending?.let { p -> { _ -> mark.done(p) } },
        onError = pending?.let { p -> { _ -> mark.done(p) } },
    )
}

/**
 * How many pictures below it are still on their way: every [ArtworkImage]
 * under a [LocalPendingArtwork] counts itself in while it loads and out
 * when it has drawn, or failed, or left.
 *
 * For a layout that is about to appear — the wall when a page closes beside
 * it — to wait until this reaches zero, and arrive with its pictures drawn
 * instead of fading in over a row of "reading" placeholders that then
 * crossfade to their posters one by one, which is what it used to do.
 * Web analogy: waiting on `img.decode()` for every image before revealing
 * a section.
 */
@Stable
class PendingArtwork {
    var count by mutableIntStateOf(0)
        private set

    internal fun started() {
        count++
    }

    internal fun finished() {
        if (count > 0) count--
    }
}

/** The [PendingArtwork] the pictures below report into. Null everywhere nothing is waiting on them. */
val LocalPendingArtwork = staticCompositionLocalOf<PendingArtwork?> { null }

/** Whether one picture is counted in a [PendingArtwork], so it is counted in once and out once. */
private class PendingMark {
    private var counted = false

    fun start(pending: PendingArtwork) {
        if (!counted) {
            counted = true
            pending.started()
        }
    }

    fun done(pending: PendingArtwork) {
        if (counted) {
            counted = false
            pending.finished()
        }
    }
}

/**
 * The poster tile (design section 01, "Media tiles"; section 05). 2:3 at
 * 12dp corners in a grid, a 4dp gap to the name at 600 12/14 and the
 * meta at 400 11/1.2. Over the art: a collection badge top-left (6dp),
 * the unwatched dot top-right (7dp), the chip bottom-left
 * (6dp in, 8dp up) and a 3dp progress bar along the bottom edge.
 *
 * A video always wears a name: a title parsed from it ("Arrival (2016)"),
 * or its own file name when nothing parses, as with most home videos. The
 * design's grey "No match" in its place belonged to a film library, and a
 * folder of clips is not one (the owner's model, 2026-10-05). [dimmed] is
 * the out-of-reach state (45%).
 */
@Composable
fun MediaTile(
    artwork: ArtworkRequest?,
    title: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    kind: ArtworkKind = artwork?.kind ?: ArtworkKind.POSTER,
    meta: String? = null,
    /**
     * The chip in the art's bottom-left corner: one short fact about what
     * the tile opens. A resolution on a film ("4K", "1080p"), the time a
     * point of interest starts at in Search, a clip's length in Shorts' Up
     * next.
     */
    chip: String? = null,
    count: Int? = null,
    unwatched: Boolean = false,
    dimmed: Boolean = false,
    /** 0..1 watched fraction; draws the 3dp bar along the bottom edge. */
    progress: Float? = null,
    fallbackLabel: String = title,
    shape: Shape = TileShape,
    /** Wide window only: this is the title open in the detail pane beside the wall. */
    selected: Boolean = false,
    /**
     * A momentary white ring, 0..1: "here is the one you came for". Shorts'
     * Locate rings the clip it just left, then lets it fade.
     *
     * The same ring [selected] uses, because the tile's own KDoc already
     * says a white ring over artwork reads as a highlight — this is that
     * meaning, borrowed for a moment rather than held.
     */
    highlight: Float = 0f,
    /** Hold to start a multi-selection. Null means the tile has nothing to hold for. */
    onLongClick: (() -> Unit)? = null,
    /**
     * Selection mode: null when not selecting, false when pickable, true
     * when picked.
     *
     * Drawn as a ring PLUS a filled check rather than a ring alone. Over
     * artwork a white ring on its own reads as a highlight — it is what the
     * detail pane already uses — so the check is the second channel that
     * says "picked" and not "open". The pane's own ring is suppressed while
     * selecting, upstream, so one ring never means two things.
     */
    checked: Boolean? = null,
    /**
     * A separate tap target on the pick marker, so the tile itself can still
     * be opened while selecting. Same split as [ListRow]'s `onLeadingClick`,
     * and for the same reason: a collection tile is a way in, and picking it
     * swallows everything inside. Null leaves the whole tile as one target,
     * which is right for a title — there is nowhere to walk into.
     */
    onCheckClick: (() -> Unit)? = null,
) {
    val colors = RegolithTheme.colors
    val picked = checked == true
    Column(
        modifier
            .combinedClickable(interactionSource = null, indication = null, onClick = onClick, onLongClick = onLongClick)
            .testTag(testTag),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(kind.width.toFloat() / kind.height)
                // The selected tile is ringed rather than tinted: the art is
                // the content, so anything drawn over it would be read as
                // part of the picture. Outside the clip so the ring is not
                // shaved by the tile's own corners.
                .then(
                    when {
                        selected || picked -> Modifier.border(2.dp, colors.ink, shape)
                        highlight > 0f -> Modifier.border(2.dp, colors.ink.copy(alpha = highlight), shape)
                        else -> Modifier
                    },
                )
                .padding(if (selected || picked || highlight > 0f) 4.dp else 0.dp)
                .clip(shape)
                .background(colors.surface)
                .alpha(if (dimmed) 0.45f else 1f),
        ) {
            ArtworkImage(artwork, Modifier.fillMaxSize(), fallbackLabel = fallbackLabel)
            if (count != null) {
                CollectionBadge(count, Modifier.align(Alignment.TopStart).padding(6.dp))
            }
            if (checked != null) {
                TilePick(
                    picked = picked,
                    onClick = onCheckClick,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            } else if (unwatched && count == null) {
                UnwatchedDot(Modifier.align(Alignment.TopEnd).padding(5.dp))
            }
            if (chip != null) {
                Chip(chip, ChipStyle.OverArt, Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 8.dp), tight = true)
            }
            if (progress != null && progress > 0f) {
                ProgressEdge(progress, Modifier.align(Alignment.BottomStart))
            }
        }
        Spacer(Modifier.height(Spacing.s4))
        Text(title, style = TextStyles.tileName, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (meta != null) {
            Text(meta, style = TextStyles.tileMeta, color = colors.metadata, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The pick marker on a tile in selection mode: a filled ink circle with a
 * dark check when picked, a hollow ring when not.
 *
 * The mark is 20dp and its target is 38: a tap this consequential — picking
 * a collection can add a hundred files — should not need aiming, and the
 * padding buys the room without growing the drawing. When [onClick] is null
 * the whole tile is the target instead, which is right for a title.
 */
@Composable
private fun TilePick(picked: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = RegolithTheme.colors
    Box(
        modifier
            .size(38.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = null, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(20.dp)
                .background(if (picked) colors.ink else colors.overArt, PillShape)
                .then(if (picked) Modifier else Modifier.border(1.5.dp, colors.onMediaCircleBorder, PillShape)),
            contentAlignment = Alignment.Center,
        ) {
            if (picked) {
                Icon(
                    painterResource(R.drawable.rg_ic_check),
                    contentDescription = "Picked",
                    tint = colors.ground,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

/** The 3dp bar at the bottom edge of art: white 25% track, red fill. */
@Composable
fun ProgressEdge(fraction: Float, modifier: Modifier = Modifier) {
    val colors = RegolithTheme.colors
    Box(modifier.fillMaxWidth().height(3.dp).background(colors.barWhite)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(colors.accent))
    }
}

/**
 * Home's resume card (design section 04): 186dp wide, 16:9 at 14dp
 * corners, a 42dp frosted play circle in the middle, "15m left" top-right,
 * the bar along the bottom; name at 500 13/17, meta at 400 11/1.4.
 */
@Composable
fun ResumeCard(
    artwork: ArtworkRequest?,
    title: String,
    meta: String,
    timeLeft: String,
    progress: Float,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    val colors = RegolithTheme.colors
    Column(modifier.clickable(interactionSource = null, indication = null, onClick = onClick).testTag(testTag)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(CardShape).background(colors.surface)) {
            ArtworkImage(artwork, Modifier.fillMaxSize(), fallbackLabel = title)
            Box(
                Modifier.align(Alignment.Center).size(42.scaledDp()).background(Color(0x24FFFFFF), PillShape).border(1.dp, Color(0x47FFFFFF), PillShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.rg_ic_play), contentDescription = "Play", tint = colors.ink, modifier = Modifier.size(15.scaledDp()))
            }
            Chip(timeLeft, ChipStyle.OverArt, Modifier.align(Alignment.TopEnd).padding(Spacing.s8))
            ProgressEdge(progress, Modifier.align(Alignment.BottomStart))
        }
        Spacer(Modifier.height(Spacing.s8))
        Text(title, style = TextStyles.rowLabelSmall, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(Spacing.s2))
        Text(meta, style = TextStyles.meta, color = colors.metadata, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The reading state: the design's dark gradient with a soft highlight and a "Reading" chip. Stillness rather than shimmer. */
@Composable
private fun ReadingArt() {
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1C2228), Color(0xFF0B0E11))))) {
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0x24FFFFFF), Color.Transparent), radius = 400f)))
        Chip("Reading", ChipStyle.OverArt, Modifier.align(Alignment.Center))
    }
}

/** No picture to show: a warm dark gradient with a blurred highlight and the filename set at 700 11/13 in the middle. */
@Composable
private fun NoPictureArt(label: String, modifier: Modifier = Modifier) {
    Box(modifier.background(Brush.linearGradient(listOf(Color(0xFF3A3A22), Color(0xFF14140A)))), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0x33FFFFFF), Color.Transparent), radius = 300f)))
        if (label.isNotEmpty()) {
            val ext = Regex("""\.([A-Za-z0-9]{2,4})$""").find(label)
            val shown = if (ext != null) label.substring(0, ext.range.first) + "\n." + ext.groupValues[1].uppercase() else label
            Text(
                shown, style = TextStyles.tileFilename, color = Color.White, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(Spacing.s8),
            )
        }
    }
}

/**
 * The narrowest a 16:9 [MediaTile] gets before a grid of them drops a
 * column: Search's results, and the moments on a collection's profile.
 */
val ThumbTileMin = 160.dp

/** How many 16:9 tiles go across [width]: as many as fit at [ThumbTileMin], never fewer than two or more than four. */
fun thumbColumns(width: Dp): Int = (width / ThumbTileMin).toInt().coerceIn(2, 4)

/**
 * [thumbColumns] as a lazy grid's column rule, the cells sharing the width
 * evenly as [GridCells.Fixed] does. Web analogy: a CSS grid's
 * `repeat(auto-fill, minmax(160px, 1fr))`, capped at four tracks.
 */
object ThumbCells : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val count = thumbColumns(availableSize.toDp())
        val usable = availableSize - spacing * (count - 1)
        return List(count) { usable / count + if (it < usable % count) 1 else 0 }
    }
}
