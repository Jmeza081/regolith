package com.regolith.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.regolith.ui.adaptive.LocalWindowShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.scaledDp

/*
 * Empty states (the empty-states spike, direction C, "ghost content").
 *
 * Every screen that can have nothing to show says so the same way: a short
 * heading in the text face, a line of body copy, at most one button and one
 * link, and under them a faint outline of the content that will fill the
 * space. No card, no dashed frame, no display face. The outline is what
 * tells you what the screen is FOR; the words only have to say why it is
 * empty and what to do next.
 *
 * Web analogy: the skeleton a page renders while it loads, kept on screen
 * as the empty state, except it never pulses. A shimmer means "wait", and
 * an empty state is the answer, not a wait.
 */

/**
 * One tappable thing under an empty state's message.
 *
 * @param primary the red pill: the one thing this screen wants you to do.
 *   False draws the frosted pill, for an action worth offering that is
 *   not the point of the screen ("Scan again" when the last scan found
 *   nothing).
 */
data class EmptyAction(
    val label: String,
    val onClick: () -> Unit,
    val testTag: String,
    val primary: Boolean = true,
)

/**
 * The outline drawn under an empty state's message: the shape of what will
 * fill the space, as that screen lays it out. Pick the one that matches the
 * content the screen shows when it is full, so the outline is a preview
 * rather than a decoration. Every ghost is static and fades out downwards.
 */
sealed interface Ghost {
    /**
     * Rows of 2:3 posters with a name under each, as many across as a wall
     * puts on this width (three on a phone). The Library's wall.
     */
    data class Posters(val rows: Int = 2) : Ghost

    /**
     * Rows of 16:9 frames, each with a time chip in the corner and a name
     * under it, as many across as [thumbColumns] fits unless the screen's
     * own grid fixes [columns]. Moments, Continue watching, Search's results.
     */
    data class Frames(val rows: Int = 1, val columns: Int? = null) : Ghost

    /** List rows led by a 16:9 thumbnail (files, downloads). */
    data class Rows(val count: Int = 3) : Ghost

    /** List rows led by a 2:3 poster (the Library in rows mode). */
    data class PosterRows(val count: Int = 3) : Ghost

    /** List rows led by a folder's icon box (shares, folders, servers). */
    data class Folders(val count: Int = 3) : Ghost

    /** One row of tall 9:16 clips (Shorts). */
    data object Clips : Ghost

    /** Home's two shelves: frames to pick up, then posters newly added. */
    data object Shelves : Ghost
}

/**
 * The empty state: a heading, a line of body copy, an optional button and
 * link, and a [Ghost] of the content to come under them.
 *
 * Left-aligned, unframed, and placed where the content would start, inside
 * the screen's own gutters: give it the same padding the content would have.
 * The words cap at a readable measure on a wide screen; the ghost spans the
 * width, because the content it stands in for does.
 *
 * Copy: [title] says what will be here or why nothing is ("Your posters will
 * line up here", "No matches for “dune”"), in sentence case. [body] says how
 * to fill it. Neither repeats the screen's name.
 *
 * @param compact the smaller heading and tighter spacing, for an empty state
 *   inside a page that already has a header of its own (a collection's
 *   Moments, a section of Home). A whole screen with nothing on it is not
 *   compact.
 * @param action the button. [EmptyAction.primary] picks red or frosted.
 * @param link a quieter text action beside the button, or on its own under
 *   the body ("Clear the search").
 * @param note small print under the actions, for what happens after the tap
 *   ("Android asks next.").
 * @param content anything else that belongs with the words, under the body:
 *   Shorts' measuring progress.
 * @param testTag on the whole block; the actions carry their own.
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    ghost: Ghost? = null,
    compact: Boolean = false,
    action: EmptyAction? = null,
    link: EmptyAction? = null,
    note: String? = null,
    testTag: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = RegolithTheme.colors
    Column(modifier.fillMaxWidth().then(if (testTag != null) Modifier.testTag(testTag) else Modifier)) {
        Column(Modifier.widthIn(max = MESSAGE_MAX_WIDTH), verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
            Text(
                title,
                style = if (compact) TextStyles.emptyHeadingSmall else TextStyles.emptyHeading,
                color = colors.ink,
                // TalkBack's heading navigation lands here first, as it would
                // on the content's own first heading.
                modifier = Modifier.semantics { heading() },
            )
            body?.let { Text(it, style = TextStyles.body, color = colors.body) }
            content?.invoke(this)
            if (action != null || link != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s18),
                    // A link alone sits close under the words it belongs to;
                    // a button gets a little air above it.
                    modifier = Modifier.padding(top = if (action != null) Spacing.s8 else 0.dp),
                ) {
                    action?.let {
                        if (it.primary) {
                            PrimaryButton(it.label, it.onClick, it.testTag, compact = true)
                        } else {
                            SecondaryButton(it.label, it.onClick, it.testTag, compact = true)
                        }
                    }
                    link?.let { TertiaryButton(it.label, it.onClick, it.testTag, inline = true, muted = action != null) }
                }
            }
            note?.let { Text(it, style = TextStyles.meta, color = colors.metadata) }
        }
        ghost?.let { GhostOutline(it, Modifier.padding(top = if (compact) Spacing.s18 else Spacing.s30)) }
    }
}

/** The words stop at about 60 characters a line on a wide screen; the ghost does not. */
private val MESSAGE_MAX_WIDTH = 480.dp

/**
 * Draws [ghost] as one canvas: every outline is a rounded rectangle, so there
 * is nothing for TalkBack to read and nothing to lay out but numbers. The
 * height follows from the width, which is why it measures first.
 */
@Composable
private fun GhostOutline(ghost: Ghost, modifier: Modifier = Modifier) {
    val wide = LocalWindowShape.current.wide
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val plan = remember(ghost, maxWidth, wide) { planGhost(ghost, maxWidth, wide) }
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(plan.height)
                // Offscreen so the fade below can cut alpha out of what is
                // already drawn (DstIn), the canvas version of a CSS
                // `mask-image: linear-gradient(...)`.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; clip = true }
                .drawBehind {
                    val hairline = 1.dp.toPx()
                    plan.pieces.forEach { p ->
                        val topLeft = Offset(p.x.toPx(), p.y.toPx())
                        val size = Size(p.w.toPx(), p.h.toPx())
                        val corner = CornerRadius(p.radius.toPx())
                        drawRoundRect(p.ink.color, topLeft, size, corner)
                        if (p.ink == Ink.Tile) {
                            // Inset by half the stroke so the hairline sits inside the tile.
                            drawRoundRect(
                                GHOST_LINE,
                                topLeft + Offset(hairline / 2, hairline / 2),
                                Size(size.width - hairline, size.height - hairline),
                                corner,
                                style = Stroke(hairline),
                            )
                        }
                    }
                    drawRect(
                        Brush.verticalGradient(0f to Color.Black, FADE_END to Color.Transparent),
                        blendMode = BlendMode.DstIn,
                    )
                },
        )
    }
}

/*
 * The ghost's three greys, all white at a few percent so they sit on any
 * of the app's near-blacks: a tile's fill, its hairline, and a line of text.
 * Faint enough to read as "something goes here", never as content.
 */
private val GHOST_FILL = Color.White.copy(alpha = 0.035f)
private val GHOST_LINE = Color.White.copy(alpha = 0.06f)
private val GHOST_TEXT = Color.White.copy(alpha = 0.05f)

/** Where the fade reaches nothing, as a fraction of the ghost's height. */
private const val FADE_END = 0.9f

private enum class Ink(val color: Color) {
    /** A tile: fill plus hairline. */
    Tile(GHOST_FILL),

    /** A name or a title. */
    Text(GHOST_TEXT),

    /** The line under a title: metadata. */
    Meta(GHOST_FILL),

    /** A chip over a tile (a moment's time). */
    Chip(GHOST_LINE),
}

/** One rounded rectangle, in dp from the ghost's top left. */
private class Piece(val x: Dp, val y: Dp, val w: Dp, val h: Dp, val radius: Dp, val ink: Ink)

private class GhostPlan(val pieces: List<Piece>, val height: Dp)

/*
 * Sizes from the direction-C frames, in the design's px through scaledDp
 * like every other fixed size; the gaps are the real grids' own (Spacing.s8),
 * which are never scaled.
 */
private val GAP = Spacing.s8
private val TILE_RADIUS = 12.dp
private val NAME_BAR = 7.scaledDp()
private val TITLE_BAR = 8.scaledDp()
private val NAME_GAP = 6.scaledDp()
private val CHIP_W = 30.scaledDp()
private val CHIP_H = 14.scaledDp()
private val CHIP_INSET = 6.scaledDp()
private val ROW_HEIGHT = 56.scaledDp()

/** A poster on a wide screen is at least this wide, as the wall's own are (see the Library's wallColumns). */
private val WIDE_POSTER_MIN = 104.dp

/** Names are not all one length; a run of equal bars reads as a table, not a shelf. */
private val NAME_WIDTHS = floatArrayOf(0.70f, 0.55f, 0.65f, 0.50f, 0.72f, 0.60f, 0.58f)
private val META_WIDTHS = floatArrayOf(0.45f, 0.38f, 0.42f, 0.35f)

private fun planGhost(ghost: Ghost, width: Dp, wide: Boolean): GhostPlan {
    val pieces = mutableListOf<Piece>()
    var y = 0.dp

    fun tile(x: Dp, top: Dp, w: Dp, h: Dp, radius: Dp = TILE_RADIUS) {
        pieces += Piece(x, top, w, h, radius, Ink.Tile)
    }

    fun bar(x: Dp, top: Dp, w: Dp, h: Dp, ink: Ink = Ink.Text) {
        pieces += Piece(x, top, w, h, h / 2, ink)
    }

    // Tiles in rows, each with a name under it unless [named] is false.
    fun grid(columns: Int, rows: Int, aspect: Float, chip: Boolean = false, named: Boolean = true) {
        val tw = (width - GAP * (columns - 1)) / columns
        val th = tw / aspect
        repeat(rows) { r ->
            repeat(columns) { c ->
                val x = (tw + GAP) * c
                tile(x, y, tw, th)
                if (chip) pieces += Piece(x + CHIP_INSET, y + th - CHIP_INSET - CHIP_H, CHIP_W, CHIP_H, CHIP_H / 2, Ink.Chip)
                if (named) bar(x, y + th + NAME_GAP, tw * NAME_WIDTHS[(r * columns + c) % NAME_WIDTHS.size], NAME_BAR)
            }
            y += th + (if (named) NAME_GAP + NAME_BAR else 0.dp) + GAP
        }
        y -= GAP
    }

    // List rows: the leading shape, centred, then a title line and a meta line.
    fun rows(count: Int, leadW: Dp, leadH: Dp, leadRadius: Dp) {
        val textX = leadW + Spacing.s12
        // A name runs as long as it is, but a bar across a wide screen reads as a rule.
        val textW = minOf(width - textX, 320.dp)
        repeat(count) { i ->
            tile(0.dp, y + (ROW_HEIGHT - leadH) / 2, leadW, leadH, leadRadius)
            bar(textX, y + ROW_HEIGHT / 2 - TITLE_BAR - 3.dp, textW * NAME_WIDTHS[i % NAME_WIDTHS.size], TITLE_BAR)
            bar(textX, y + ROW_HEIGHT / 2 + 3.dp, textW * META_WIDTHS[i % META_WIDTHS.size], NAME_BAR, Ink.Meta)
            y += ROW_HEIGHT
        }
    }

    when (ghost) {
        is Ghost.Posters -> {
            val columns = if (wide) ((width + GAP) / (WIDE_POSTER_MIN + GAP)).toInt().coerceIn(3, 7) else 3
            grid(columns, ghost.rows, 2f / 3f)
        }
        is Ghost.Frames -> grid(ghost.columns ?: thumbColumns(width), ghost.rows, 16f / 9f, chip = true)
        is Ghost.Rows -> rows(ghost.count, 52.scaledDp(), 29.25.scaledDp(), 7.dp)
        is Ghost.PosterRows -> rows(ghost.count, 34.scaledDp(), 51.scaledDp(), 7.dp)
        is Ghost.Folders -> rows(ghost.count, 34.scaledDp(), 34.scaledDp(), 10.dp)
        Ghost.Clips -> grid(if (wide) 5 else 3, 1, 9f / 16f, named = false)
        Ghost.Shelves -> {
            // Home's own proportions: on a phone two frames and three posters
            // to a row, on a wide screen three and six, as its rows lay out.
            val shelf = { columns: Int, aspect: Float, label: Float ->
                bar(0.dp, y, width * label, TITLE_BAR)
                y += TITLE_BAR + Spacing.s12
                grid(columns, 1, aspect, named = false)
                y += Spacing.s30
            }
            shelf(if (wide) 3 else 2, 16f / 9f, 0.30f)
            shelf(if (wide) 6 else 3, 2f / 3f, 0.22f)
            y -= Spacing.s30
        }
    }
    return GhostPlan(pieces, y)
}
