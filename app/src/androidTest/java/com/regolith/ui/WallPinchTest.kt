package com.regolith.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.regolith.ui.components.WallPinchPill
import com.regolith.ui.components.rememberWallPinch
import com.regolith.ui.components.wallPinch
import com.regolith.ui.theme.RegolithTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The wall pinch, driven by real two-finger gestures through Compose's own
 * touch injection: spreading steps to fewer posters across and pinching in
 * to more, one count per step, never past four or what fits, with the
 * "N across" pill up while it happens. One finger is left alone, which is
 * what keeps a wall scrollable under it.
 *
 * The clock is held still so the pill can be checked before it fades; it
 * lingers 900ms after the fingers lift.
 *
 * A wall can also stop being pinchable with the fingers still down (folding
 * the phone shut drops the pinch from it), and the pill must still go rather
 * than stay up with nothing left to end it.
 *
 * And on a long wall scrolled part way down, the poster a pinch began on
 * stays where it was as the columns change, even when the steps come faster
 * than the frames.
 */
class WallPinchTest {

    @get:Rule
    val rule = createComposeRule()

    private val steps = mutableListOf<Int>()
    private var pinchable by mutableStateOf(true)

    private fun wall(shown: Int, most: Int) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            RegolithTheme {
                val pinch = rememberWallPinch(shown = shown, most = most, onStep = { steps += it })
                Box(Modifier.size(400.dp).wallPinch(pinch.takeIf { pinchable }).testTag("wall")) {
                    WallPinchPill(pinch, Modifier.align(Alignment.TopCenter))
                }
            }
        }
    }

    /** Two fingers from 40px apart to 360px apart (or back), across the middle. */
    private fun spread(out: Boolean) {
        rule.onNodeWithTag("wall").performTouchInput {
            val near = Offset(20f, 0f)
            val far = Offset(180f, 0f)
            pinch(
                start0 = center - if (out) near else far, end0 = center - if (out) far else near,
                start1 = center + if (out) near else far, end1 = center + if (out) far else near,
                durationMillis = 400,
            )
        }
        rule.mainClock.advanceTimeBy(100)
    }

    @Test
    fun spreadingStepsToFewerAcrossAndStopsAtFour() {
        wall(shown = 7, most = 7)
        spread(out = true)
        assertEquals(listOf(6, 5, 4), steps)
        rule.onNodeWithTag("wall_pinch_pill").assertIsDisplayed()
        rule.onNodeWithText("4 across").assertIsDisplayed()
    }

    @Test
    fun pinchingInStepsToMoreAcrossButNoFurtherThanFits() {
        wall(shown = 4, most = 5)
        spread(out = false)
        assertEquals(listOf(5), steps)
        rule.onNodeWithText("5 across").assertIsDisplayed()
    }

    @Test
    fun oneFingerIsLeftAlone() {
        wall(shown = 6, most = 7)
        rule.onNodeWithTag("wall").performTouchInput { swipeUp() }
        rule.mainClock.advanceTimeBy(100)
        assertEquals(emptyList<Int>(), steps)
        rule.onNodeWithTag("wall_pinch_pill").assertDoesNotExist()
    }

    @Test
    fun aWallThatStopsBeingPinchableMidPinchStillLetsThePillGo() {
        wall(shown = 6, most = 7)
        rule.onNodeWithTag("wall").performTouchInput {
            down(0, center - Offset(40f, 0f))
            down(1, center + Offset(40f, 0f))
        }
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithText("6 across").assertIsDisplayed()

        pinchable = false
        rule.mainClock.advanceTimeBy(1_500)
        rule.onNodeWithTag("wall_pinch_pill").assertDoesNotExist()
        rule.onNodeWithTag("wall").performTouchInput { up(1); up(0) }
    }

    @Test
    fun aScrolledWallKeepsThePinchedPosterInView() {
        var perRow by mutableIntStateOf(7)
        // Row 20 at the top: 60dp-wide posters, 90dp tall, nine rows on screen.
        val grid = LazyGridState(firstVisibleItemIndex = 140)
        rule.setContent {
            RegolithTheme {
                val pinch = rememberWallPinch(shown = perRow, most = 7, onStep = { perRow = it })
                LazyVerticalGrid(
                    columns = GridCells.Fixed(perRow),
                    state = grid,
                    modifier = Modifier.size(420.dp, 840.dp).wallPinch(pinch, grid).testTag("wall"),
                ) {
                    items(300, key = { it }) { Box(Modifier.aspectRatio(2f / 3f).testTag("poster_$it")) }
                }
            }
        }
        // The middle of the wall is row 24's fourth poster, 360dp down.
        val pinched = rule.onNodeWithTag("poster_171")
        assertEquals(360f, pinched.getUnclippedBoundsInRoot().top.value, 1f)

        rule.onNodeWithTag("wall").performTouchInput {
            pinch(
                start0 = center - Offset(20f, 0f), end0 = center - Offset(180f, 0f),
                start1 = center + Offset(20f, 0f), end1 = center + Offset(180f, 0f),
                durationMillis = 400,
            )
        }
        rule.waitForIdle()

        // Four across now, so 105dp posters: poster 140, which the grid alone
        // would have kept at the top, is seven rows (1102dp) above this one.
        assertEquals(4, perRow)
        pinched.assertIsDisplayed()
        assertEquals(360f, pinched.getUnclippedBoundsInRoot().top.value, 2f)
    }
}
