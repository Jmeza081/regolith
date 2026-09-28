package com.regolith.ui.shorts

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.regolith.domain.playback.Filmstrip
import com.regolith.ui.components.StripFrame
import com.regolith.ui.theme.RegolithTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The Shorts panel beside a clip on the inner display held sideways.
 *
 * Pinned here because the demo library cannot show half of it: a demo file
 * has no share folder, so Make a poster never appears on the emulator. The
 * owner's rules for the panel are what these check — a frame is a place to
 * jump to, never a pause; pausing is what offers a poster; Up next is the
 * deck after the clip on screen.
 */
class ShortsPanelTest {

    @get:Rule
    val compose = createComposeRule()

    private val clips = (1L..6L).map { id ->
        ShortItem(fileId = id, name = "2026091${id}_183022", folderLabel = "Phone", meta = "0:18 · 1080p", folderId = 9, durationMs = 18_000)
    }
    private val state = ShortsUiState(
        loaded = true,
        items = clips,
        folders = listOf(ShortsFolder(9, "Phone", clips.size)),
        shuffled = true,
    )
    private val strip = Filmstrip.positions(18_000, 8).map { StripFrame(it, null) }

    private val seeks = mutableListOf<Long>()
    private val jumps = mutableListOf<Int>()
    private val skips = mutableListOf<Long>()
    private val picks = mutableListOf<Long?>()
    private var posters = 0
    private var viewAlls = 0

    private fun show(
        index: Int = 1,
        playing: Boolean,
        canMakePoster: Boolean = true,
        atMs: Long = 7_000,
        state: ShortsUiState = this.state,
    ) {
        val clock = ClipClock().apply {
            positionMs = atMs
            durationMs = 18_000
            this.playing = playing
        }
        compose.setContent {
            RegolithTheme {
                ShortsPanel(
                    item = clips[index],
                    state = state,
                    index = index,
                    strip = strip,
                    clock = clock,
                    autoAdvance = false,
                    canMakePoster = canMakePoster,
                    onLocate = {},
                    onKeep = {},
                    onSound = {},
                    onSeek = { seeks += it },
                    onMakePoster = { posters++ },
                    onPickFolder = { picks += it },
                    onViewAll = { viewAlls++ },
                    onShuffle = {},
                    onAutoAdvance = {},
                    onJumpTo = { jumps += it },
                    onSkip = { skips += it.fileId },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    @Test
    fun whilePlayingThereIsNoPosterButtonOnlyTheWayToOne() {
        show(playing = true)
        compose.onNodeWithTag("shorts_strip_hint").assertIsDisplayed()
        compose.onNodeWithText("Tap a frame to jump there. Pause to make a poster.").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTagCount("shorts_make_poster") == 0)
    }

    @Test
    fun pausedOffersAPosterFromThatMoment() {
        show(playing = false, atMs = 7_400)
        compose.onNodeWithTag("shorts_make_poster").assertIsDisplayed()
        compose.onNodeWithText("from 0:07").assertIsDisplayed()
        compose.onNodeWithTag("shorts_make_poster").performClick()
        assertEquals(1, posters)
    }

    @Test
    fun aClipWithNowhereToPutAPosterNeverOffersOne() {
        show(playing = false, canMakePoster = false)
        assertTrue(compose.onAllNodesWithTagCount("shorts_make_poster") == 0)
        compose.onNodeWithText("Tap a frame to jump there.").assertIsDisplayed()
    }

    @Test
    fun tappingAFrameSeeksThereAndDoesNothingElse() {
        show(playing = true)
        val target = strip[5].positionMs
        compose.onNodeWithTag("shorts_strip_$target").performClick()
        assertEquals(listOf(target), seeks)
        assertEquals("a frame is not a poster button", 0, posters)
    }

    @Test
    fun upNextIsTheDeckAfterTheClipOnScreen() {
        show(index = 1, playing = true)
        compose.onNodeWithText("UP NEXT · 2 OF 6").assertIsDisplayed()
        // Clips 1 and 2 are behind and on screen; the tiles start at clip 3.
        assertTrue(compose.onAllNodesWithTagCount("shorts_next_1") == 0)
        assertTrue(compose.onAllNodesWithTagCount("shorts_next_2") == 0)
        compose.onNodeWithTag("shorts_next_4").performScrollTo().performClick()
        assertEquals("clip 4 is item 3 of the deck", listOf(3), jumps)
        compose.onNodeWithTag("shorts_skip_3").performScrollTo().performClick()
        assertEquals(listOf(3L), skips)
    }

    @Test
    fun aShareWithManyFoldersShowsTheBiggestFourAndAWayToTheRest() {
        // Fifty folders is what the owner's share has; a row of fifty chips
        // is a row nobody reads.
        val folders = (1L..50L).map { id -> ShortsFolder(id, "Folder $id", count = id.toInt()) }
        show(playing = true, state = state.copy(folders = folders))
        compose.onNodeWithTag("shorts_chip_all").assertIsDisplayed()
        // The four holding the most clips: 50, 49, 48 and 47.
        for (id in 47L..50L) compose.onNodeWithTag("shorts_chip_$id").assertExists()
        assertTrue(compose.onAllNodesWithTagCount("shorts_chip_46") == 0)
        compose.onNodeWithTag("shorts_chip_view_all").performScrollTo().performClick()
        assertEquals(1, viewAlls)
    }

    @Test
    fun aFewFoldersNeedNoViewAll() {
        show(playing = true)
        assertTrue(compose.onAllNodesWithTagCount("shorts_chip_view_all") == 0)
        compose.onNodeWithTag("shorts_chip_9").performClick()
        assertEquals(listOf<Long?>(9L), picks)
    }

    @Test
    fun theSoundButtonSitsWithTheTitle() {
        show(playing = true)
        compose.onNodeWithTag("shorts_panel_sound").assertIsDisplayed()
        compose.onNodeWithTag("shorts_panel_title").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}
