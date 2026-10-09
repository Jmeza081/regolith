package com.regolith.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.regolith.ui.theme.CardShape
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.designSp
import com.regolith.ui.theme.scaledDp
import java.text.Normalizer
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Which letter of an [AlphabetRail] each name files under, and where each
 * letter's run starts, for a list that is ALREADY in alphabetical order.
 *
 * The rail follows the list; it never sorts anything, so a jump always lands
 * on a row that is really there. A name files under its first letter with
 * any accent taken off ("Élan" under E); anything else — a digit, a
 * bracket, another alphabet — files under "#".
 *
 * Plain Kotlin with no Android in it, so it is unit-tested off the device.
 */
class AlphabetIndex(names: List<String>) {

    /** Letter → position of the first name under it. Only the first counts: that is where the rail jumps. */
    private val starts: Map<Char, Int> = buildMap {
        names.forEachIndexed { i, name -> putIfAbsent(letterOf(name), i) }
    }

    /** The letters with at least one name under them — the rail draws these brighter. */
    val present: Set<Char> get() = starts.keys

    /** Where [letter]'s names start in the list, or null when nothing files under it. */
    fun positionOf(letter: Char): Int? = starts[letter]

    companion object {
        /**
         * Every rail shows all 27, always in this order, so a letter is in the
         * same place on every list. "#" leads because digits and brackets sort
         * ahead of letters.
         */
        val LETTERS: List<Char> = listOf('#') + ('A'..'Z')

        /** The letter [name] files under. */
        fun letterOf(name: String): Char {
            val first = name.trimStart().firstOrNull() ?: return '#'
            // NFKD splits "É" into "E" plus a combining accent (and a
            // full-width "Ａ" into a plain "A"); the base letter comes first.
            val base = Normalizer.normalize(first.toString(), Normalizer.Form.NFKD).first().uppercaseChar()
            return if (base in 'A'..'Z') base else '#'
        }
    }
}

/** How wide the strip is: its touch target, most of which sits in the gutter it is placed over. */
val AlphabetRailWidth = 32.dp

/**
 * More items than this, and a list gets an [AlphabetRail]: the owner's line
 * between a list you read and one you hunt through. The move sheet's
 * folders and the Library's walls both draw it there.
 */
const val RAIL_AFTER = 10

/**
 * An A–Z strip down the end edge of a long alphabetical list. Touch it and
 * slide, and the list jumps to each letter the finger crosses; a bubble
 * beside the finger shows the letter, because the finger hides the strip.
 *
 * Give it the LIST'S area (`Modifier.matchParentSize()` in a Box around the
 * list): the strip draws [AlphabetRailWidth] wide at the end edge and the
 * bubble floats to its left. Only the strip takes touches — the rest of the
 * area lets them through to the rows — so give the list enough end padding
 * that no row's own target sits under the strip.
 *
 * All 27 places are always drawn ("#" then A–Z), so a letter never moves.
 * The ones with nothing under them are dimmed and do nothing, like a
 * disabled button. On a list too short to letter all 27, only every second
 * or third is labelled, but every place still answers to the finger — the
 * bubble says which one it is on.
 *
 * Web analogy: the A–Z index down the side of a contacts page, where each
 * letter is an in-page anchor.
 *
 * @param onJump called when the finger lands on, or slides onto, a letter
 *   that has names. [position] is where that letter's run starts in the
 *   list [index] was built from. The CALLER scrolls: the rail works for any
 *   list or grid, so it never touches one.
 */
@Composable
fun AlphabetRail(
    index: AlphabetIndex,
    onJump: (letter: Char, position: Int) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    val colors = RegolithTheme.colors
    val haptics = LocalHapticFeedback.current
    // Read from inside the gesture, which outlives any one composition: a
    // restarted pointerInput would drop the finger mid-slide.
    val currentIndex by rememberUpdatedState(index)
    val currentOnJump by rememberUpdatedState(onJump)
    // The letter under the finger while the strip is held, else null.
    var held by remember { mutableStateOf<Char?>(null) }
    // What the bubble says. Outlives `held`, so the bubble can fade out still
    // showing the letter it was showing rather than going blank first.
    var shown by remember { mutableStateOf('#') }
    // Where the finger is, in px from the top. Read only in the bubble's
    // offset lambda (the layout phase), so a slide moves the bubble without
    // recomposing anything.
    var fingerY by remember { mutableFloatStateOf(0f) }

    val letterStyle = TextStyles.navLabel
    val lineHeight = with(LocalDensity.current) { letterStyle.lineHeight.toDp() }

    BoxWithConstraints(modifier) {
        val slot = min(MaxSlot, maxHeight / AlphabetIndex.LETTERS.size)
        // Label every place while the labels fit, then every second, third...
        // Measured against the style's line height in dp, so a large system
        // font thins the labels instead of stacking them on top of each other.
        val labelEvery = railLabelEvery(slot.value, lineHeight.value)

        Box(
            Modifier.align(Alignment.CenterEnd).width(AlphabetRailWidth).fillMaxHeight()
                .semantics { contentDescription = "Jump to a letter" }
                .testTag(testTag)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        // Consumed from the first touch, so neither the list
                        // nor a bottom sheet's drag-to-dismiss sees the slide.
                        val down = awaitFirstDown()
                        down.consume()
                        fun land(y: Float) {
                            fingerY = y
                            val letter = AlphabetIndex.LETTERS[railLetterAt(y, size.height.toFloat(), MaxSlot.toPx())]
                            if (letter == held) return
                            held = letter
                            shown = letter
                            val position = currentIndex.positionOf(letter) ?: return
                            // A tick per letter that moves the list: the
                            // dimmed ones are felt as nothing, as they do nothing.
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                            currentOnJump(letter, position)
                        }
                        try {
                            land(down.position.y)
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) break
                                land(change.position.y)
                            }
                        } finally {
                            // Also on cancellation — the sheet dismissed mid-slide.
                            held = null
                        }
                    }
                },
        ) {
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = RailInset)
                    // Lit while held, so the strip reads as the thing in your hand.
                    .background(if (held != null) colors.badgeBg else Color.Transparent, PillShape),
            ) {
                AlphabetIndex.LETTERS.forEachIndexed { i, letter ->
                    val tag = if (letter == '#') "digits" else letter.toString()
                    Box(Modifier.height(slot).fillMaxWidth().testTag("${testTag}_$tag"), contentAlignment = Alignment.Center) {
                        if (i % labelEvery == 0) {
                            Text(
                                letter.toString(),
                                style = letterStyle,
                                color = when {
                                    letter == held -> colors.ink
                                    letter in index.present -> colors.navIdle
                                    else -> colors.disabledInk
                                },
                                maxLines = 1,
                                softWrap = false,
                                // A thinned rail's label is taller than its one
                                // place: let it spill into the unlabelled places
                                // around it rather than be clipped to a sliver.
                                overflow = TextOverflow.Visible,
                                modifier = Modifier.wrapContentHeight(unbounded = true),
                            )
                        }
                    }
                }
            }
        }

        // The bubble: to the left of the strip, level with the finger, kept
        // inside the list's area so it never sits over the sheet's header.
        AnimatedVisibility(
            visible = held != null,
            enter = fadeIn(tween(BUBBLE_IN_MS)) + scaleIn(tween(BUBBLE_IN_MS), initialScale = 0.7f),
            exit = fadeOut(tween(BUBBLE_OUT_MS)),
            modifier = Modifier.align(Alignment.TopEnd).offset {
                val bubble = BubbleSize.roundToPx()
                val top = (fingerY - bubble / 2f).coerceIn(0f, (constraints.maxHeight - bubble).coerceAtLeast(0).toFloat())
                IntOffset(-(AlphabetRailWidth + Spacing.s8).roundToPx(), top.roundToInt())
            },
        ) {
            Box(
                Modifier.size(BubbleSize).background(colors.lifted, CardShape).border(1.dp, colors.liftedBorder, CardShape)
                    .testTag("${testTag}_bubble"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    shown.toString(),
                    style = TextStyles.rowLabel.copy(fontSize = 24.designSp(), lineHeight = 24.designSp()),
                    color = if (shown in index.present) colors.ink else colors.disabledInk,
                )
            }
        }
    }
}

/**
 * Which of the rail's places a finger at [y] is on, as an index into
 * [AlphabetIndex.LETTERS]. The places are `min(maxSlot, height / 27)` tall,
 * centred in [height]; a finger above or below them is on the first or last.
 */
internal fun railLetterAt(y: Float, height: Float, maxSlot: Float): Int {
    val count = AlphabetIndex.LETTERS.size
    val slot = minOf(maxSlot, height / count)
    if (slot <= 0f) return 0
    val top = (height - slot * count) / 2
    return ((y - top) / slot).toInt().coerceIn(0, count - 1)
}

/** Label every Nth place, where N is the smallest that keeps a [lineHeight]-tall label clear of the next one. */
internal fun railLabelEvery(slot: Float, lineHeight: Float): Int =
    if (slot <= 0f) AlphabetIndex.LETTERS.size else ceil(lineHeight / slot).toInt().coerceAtLeast(1)

/** Tallest a place gets, so a tall list keeps the letters together rather than spread down the screen. */
private val MaxSlot = 18.dp

/** Between the strip's edge and its lit background: the pill sits inside the touch target, not flush with it. */
private val RailInset = 6.dp

private val BubbleSize = 48.scaledDp()
private const val BUBBLE_IN_MS = 90
private const val BUBBLE_OUT_MS = 160
