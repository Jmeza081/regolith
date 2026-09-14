package com.regolith.ui.components

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
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
 * look while it is pulled off the share, and the unmatched look (a dark
 * gradient with the filename set inside) when nothing was found. Used by
 * [MediaTile], [ResumeCard], list-row thumbnails and Title Detail.
 *
 * [artwork] is whatever the app's image loader understands: the phone's
 * `ArtworkRequest`, or the Mac's `ShareImage` (an image file on a share).
 */
@Composable
fun ArtworkImage(
    artwork: Any?,
    modifier: Modifier = Modifier,
    fallbackLabel: String = "",
    contentScale: ContentScale = ContentScale.Crop,
) {
    if (artwork == null) {
        UnmatchedArt(fallbackLabel, modifier)
        return
    }
    SubcomposeAsyncImage(
        model = artwork,
        contentDescription = null,
        modifier = modifier,
        loading = { ReadingArt() },
        error = { UnmatchedArt(fallbackLabel, Modifier.fillMaxSize()) },
        success = { SubcomposeAsyncImageContent(contentScale = contentScale) },
    )
}

/**
 * The poster tile (design section 01, "Media tiles"; section 05). 2:3 at
 * 12dp corners in a grid, a 4dp gap to the name at 600 12/14 and the
 * meta at 400 11/1.2. Over the art: a collection badge top-left (6dp),
 * the unwatched dot top-right (7dp), the resolution chip bottom-left
 * (6dp in, 8dp up) and a 3dp progress bar along the bottom edge.
 *
 * An unmatched title draws its filename inside the art and "No match" in
 * grey where the name goes. [dimmed] is the out-of-reach state (45%).
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
    /** "4K", "1080p": the bottom-left chip. */
    resolution: String? = null,
    count: Int? = null,
    unwatched: Boolean = false,
    dimmed: Boolean = false,
    /** 0..1 watched fraction; draws the 3dp bar along the bottom edge. */
    progress: Float? = null,
    /** False draws "No match" in #6E6E6E for the name and the filename inside the art. */
    matched: Boolean = true,
    fallbackLabel: String = title,
    shape: Shape = TileShape,
    /** Wide window only: this is the title open in the detail pane beside the wall. */
    selected: Boolean = false,
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
                .then(if (selected || picked) Modifier.border(2.dp, colors.ink, shape) else Modifier)
                .padding(if (selected || picked) 4.dp else 0.dp)
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
            if (resolution != null) {
                Chip(resolution, ChipStyle.OverArt, Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 8.dp), tight = true)
            }
            if (progress != null && progress > 0f) {
                ProgressEdge(progress, Modifier.align(Alignment.BottomStart))
            }
        }
        Spacer(Modifier.height(Spacing.s4))
        Text(
            if (matched) title else "No match",
            style = TextStyles.tileName, color = if (matched) colors.ink else colors.metadata,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
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
                    RegolithIcons.Check,
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
                Icon(RegolithIcons.Play, contentDescription = "Play", tint = colors.ink, modifier = Modifier.size(15.scaledDp()))
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

/** The unmatched look: a warm dark gradient with a blurred highlight and the filename set at 700 11/13 in the middle. */
@Composable
private fun UnmatchedArt(label: String, modifier: Modifier = Modifier) {
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
