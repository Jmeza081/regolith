package com.regolith.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.regolith.domain.playback.ReelClip
import com.regolith.player.ReelState
import com.regolith.ui.player.ReelDetails
import com.regolith.ui.player.ReelList
import com.regolith.ui.player.ReelSegments
import com.regolith.ui.theme.RegolithTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The Moments reel's parts in the player (Reel.kt), with no film behind
 * them: where the reel is, what is playing and in what, and that each way
 * out does what it says.
 */
class ReelUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val reel = ReelState(
        title = "Documentaries",
        clips = listOf(
            ReelClip(41, 2_000, 5_000, "Iguana chase", "Planet Earth II S1E1"),
            ReelClip(41, 5_000, 15_000, "Snow leopards", "Planet Earth II S1E1"),
            ReelClip(42, 8_000, 18_000, "The summit", "Free Solo (2018)"),
        ),
        index = 1,
    )

    @Test
    fun theBarsSayWhichMomentOfHowMany() {
        rule.setContent { RegolithTheme { ReelSegments(reel, { 0.4f }) } }
        rule.onNodeWithContentDescription("Moment 2 of 3").assertIsDisplayed()
    }

    @Test
    fun theDetailsSayWhatIsPlayingAndWhereItIsFrom() {
        var watched = 0
        var shuffled = 0
        rule.setContent { RegolithTheme { ReelDetails(reel, { watched++ }, { shuffled++ }) } }
        rule.onNodeWithText("MOMENTS · DOCUMENTARIES").assertIsDisplayed()
        rule.onNodeWithText("Planet Earth II S1E1 · 0:05 · 2 of 3").assertIsDisplayed()
        rule.onNodeWithTag("player_reel_watch").performClick()
        rule.onNodeWithContentDescription("Shuffle the moments").performClick()
        assertEquals(1, watched)
        assertEquals(1, shuffled)
    }

    @Test
    fun theListCountsTheReelAndGoesWhereYouTap() {
        val picked = mutableListOf<Int>()
        rule.setContent { RegolithTheme { ReelList(reel, playing = 0.5f, onClip = { picked += it }) } }
        rule.onNodeWithText("3 moments · about 23 s").assertIsDisplayed()
        rule.onNodeWithContentDescription("Playing").assertIsDisplayed()
        rule.onNodeWithTag("player_reel_clip_2").performClick()
        assertEquals(listOf(2), picked)
    }

    @Test
    fun aShuffledReelSaysSo() {
        rule.setContent { RegolithTheme { ReelDetails(reel.copy(shuffled = true), {}, {}) } }
        rule.onNodeWithContentDescription("Shuffle is on. Tap to play the moments in order.").assertIsDisplayed()
    }
}
