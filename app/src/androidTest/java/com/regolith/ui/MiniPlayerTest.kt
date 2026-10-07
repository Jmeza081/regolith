package com.regolith.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.regolith.player.PlaybackState
import com.regolith.ui.player.MiniPlayerBar
import com.regolith.ui.player.MiniPlayerCard
import com.regolith.ui.theme.RegolithTheme
import dev.chrisbanes.haze.HazeState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The mini player's two shapes (MiniPlayer.kt) with no film behind them: the
 * phone's bar and the wide window's card say what is playing and where it
 * lives, their buttons do what they say, a finished film offers Play rather
 * than Pause, and the card greys out a direction with nowhere to go.
 */
class MiniPlayerTest {

    @get:Rule
    val rule = createComposeRule()

    private val playing = PlaybackState(
        loaded = true,
        fileId = 42,
        title = "Free.Solo.2018.1080p",
        sourceLabel = "MEDIA · media/Documentaries",
        playWhenReady = true,
        durationMs = 20_000,
    )

    private var expanded = 0
    private var toggled = 0
    private var closed = 0

    private fun bar(state: PlaybackState = playing) = rule.setContent {
        RegolithTheme {
            MiniPlayerBar(
                state = state,
                player = null,
                hazeState = remember { HazeState() },
                onExpand = { expanded++ },
                onTogglePlay = { toggled++ },
                onClose = { closed++ },
            )
        }
    }

    @Test
    fun theBarSaysWhatIsPlayingAndWhereItLives() {
        bar()
        rule.onNodeWithText("Free.Solo.2018.1080p").assertIsDisplayed()
        rule.onNodeWithText("0:00 · Documentaries").assertIsDisplayed()
        rule.onNodeWithContentDescription("Pause").assertIsDisplayed()
    }

    @Test
    fun theBarsButtonsDoWhatTheySay() {
        bar()
        rule.onNodeWithTag("mini_player_play").performClick()
        rule.onNodeWithTag("mini_player_close").performClick()
        rule.onNodeWithTag("mini_player_bar").performClick()
        assertEquals(1, toggled)
        assertEquals(1, closed)
        assertEquals(1, expanded)
    }

    @Test
    fun aFinishedFilmOffersPlayNotPause() {
        bar(playing.copy(ended = true))
        rule.onNodeWithContentDescription("Play").assertIsDisplayed()
    }

    @Test
    fun theCardGreysOutADirectionWithNowhereToGo() {
        var nexts = 0
        rule.setContent {
            RegolithTheme {
                MiniPlayerCard(
                    state = playing,
                    player = null,
                    hazeState = remember { HazeState() },
                    onExpand = { expanded++ },
                    onTogglePlay = { toggled++ },
                    onPrevious = null,
                    onNext = { nexts++ },
                    onClose = { closed++ },
                )
            }
        }
        rule.onNodeWithText("0:00 of 0:20").assertIsDisplayed()
        rule.onNodeWithTag("mini_player_previous").assertIsNotEnabled()
        rule.onNodeWithTag("mini_player_next").assertIsEnabled().performClick()
        rule.onNodeWithTag("mini_player_expand").performClick()
        assertEquals(1, nexts)
        assertEquals(1, expanded)
    }
}
