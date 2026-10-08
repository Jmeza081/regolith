package com.regolith.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.regolith.ui.story.tapOrHold
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * A story's touch (StoryGestures.kt): a tap steps, by the side it lands on,
 * and a hold pauses until the finger lifts and never steps. The owner's
 * report on test-3: letting go after a hold went on to the next picture.
 */
class StoryGesturesTest {

    @get:Rule
    val rule = createComposeRule()

    private val holds = mutableListOf<Boolean>()

    /** Each tap's side: true for back. */
    private val taps = mutableListOf<Boolean>()

    @Before
    fun setUp() {
        rule.setContent {
            Box(Modifier.size(300.dp).tapOrHold(onHold = { holds += it }, onTap = { taps += it }).testTag("story_stage"))
        }
    }

    @Test
    fun aTapOnTheRightGoesOn() {
        rule.onNodeWithTag("story_stage").performTouchInput { click(centerRight - Offset(20f, 0f)) }
        rule.waitForIdle()
        assertEquals(listOf(false), taps)
        assertEquals(listOf(true, false), holds)
    }

    @Test
    fun aTapOnTheLeftGoesBack() {
        rule.onNodeWithTag("story_stage").performTouchInput { click(centerLeft + Offset(20f, 0f)) }
        rule.waitForIdle()
        assertEquals(listOf(true), taps)
    }

    @Test
    fun aHoldPausesUntilTheFingerLiftsAndLettingGoDoesNotStep() {
        rule.onNodeWithTag("story_stage").performTouchInput { down(centerRight - Offset(20f, 0f)) }
        // The finger stays down for a second and a half: the test's clock
        // only moves when it is told to, so it is moved, as time would.
        rule.mainClock.advanceTimeBy(1_500)
        assertEquals(listOf(true), holds)
        assertEquals(emptyList<Boolean>(), taps)
        rule.onNodeWithTag("story_stage").performTouchInput {
            advanceEventTime(1_500)
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf(true, false), holds)
        assertEquals(emptyList<Boolean>(), taps)
    }
}
