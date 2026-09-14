package com.regolith.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import com.regolith.desktop.data.ServerStore
import com.regolith.desktop.ui.RegolithChaptersApp
import com.regolith.testing.FakeSmbGateway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * The editor's rules through the real screens, with no network and no VLC:
 * the fake SMB server and a fake player. Covers the typed start time, the
 * keyboard shortcuts, the unsaved-changes guard and Revert.
 */
@OptIn(ExperimentalTestApi::class)
class EditorFlowTest {
    private val film = "Films/Heat.1995.mkv"
    private val sidecar = "Films/Heat.1995.chapters.txt"
    private val shots = File("build/test-shots").apply { mkdirs() }

    private fun ComposeUiTest.waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) =
        waitUntil(timeoutMillis = timeoutMs) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.waitGone(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) =
        waitUntil(timeoutMillis = timeoutMs) { onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }

    private fun ComposeUiTest.key(k: Key) = onNodeWithTag("editor_root").performKeyInput { pressKey(k) }

    @Test
    fun typedStartShortcutsGuardAndRevert() = runDesktopComposeUiTest(width = 1280, height = 800) {
        val fake = FakeSmbGateway().apply {
            addFile("media", film, ByteArray(128))
            addFile("media", sidecar, "CHAPTER01=00:00:00.000\nCHAPTER01NAME=Intro\nCHAPTER02=00:02:00.000\nCHAPTER02NAME=The heist\n".toByteArray())
        }
        val player = FakeFilmPlayer().apply { durationMs = 10 * 60_000L }
        val graph = AppGraph(fake, ServerStore(Files.createTempDirectory("regolith-test").resolve("servers.json")), InMemoryCredentialStore())
        fun onShare(): String? = fake.files["media"]?.get(sidecar)?.toString(Charsets.UTF_8)
        // The topmost root: a dialog is its own root, drawn over the window.
        fun shot(name: String) = ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(shots, "flow-$name.png"))

        setContent { RegolithChaptersApp(graph, newPlayer = { player }, videoSurface = { Box(Modifier.fillMaxSize()) }) }

        // Connect with Enter, open the film.
        onNodeWithTag("servers_address_field").performTextInput("tower/media")
        onNodeWithTag("servers_address_field").performKeyInput { pressKey(Key.Enter) }
        waitFor(hasText("Films"))
        onNodeWithText("Films").performClick()
        waitFor(hasText("Heat.1995.mkv"))
        onNodeWithText("Heat.1995.mkv").performClick()
        waitFor(hasTestTag("editor_chapter_row_1"))
        waitFor(hasTestTag("editor_revert_button"))

        // Typed start: a bad time explains itself, a good one moves the mark and the film.
        onNodeWithTag("editor_chapter_row_1").performClick()
        onNodeWithTag("editor_start_field").performClick()
        onNodeWithTag("editor_start_field").performTextReplacement("later")
        onNodeWithTag("editor_start_field").performKeyInput { pressKey(Key.Enter) }
        waitFor(hasText("Use 12:30, 0:12:30 or 1:02:15.5"))
        onNodeWithTag("editor_start_field").performTextReplacement("1:30")
        onNodeWithTag("editor_start_field").performKeyInput { pressKey(Key.Enter) }
        waitFor(hasText("CHAPTER02=00:01:30.000", substring = true))
        assertEquals(90_000L, player.positionMs)
        shot("1-typed-start")

        // Esc closes the row; then the shortcuts reach the editor.
        onNodeWithTag("editor_start_field").performKeyInput { pressKey(Key.Escape) }
        waitGone(hasTestTag("editor_start_field"))
        key(Key.Spacebar)
        assertTrue("Space plays", player.playing)
        key(Key.DirectionRight)
        assertEquals("→ seeks 5 s", 95_000L, player.positionMs)
        player.positionMs = 300_000
        key(Key.M)
        waitFor(hasTestTag("editor_chapter_row_2"))
        // The new mark opened; close it before saving from the keyboard.
        key(Key.Escape)
        onNodeWithTag("editor_root").performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.S) } }
        waitFor(hasText("Saved to the share"))
        assertEquals(
            "CHAPTER01=00:00:00.000\nCHAPTER01NAME=Intro\nCHAPTER02=00:01:30.000\nCHAPTER02NAME=The heist\nCHAPTER03=00:05:00.000\nCHAPTER03NAME=\n",
            onShare(),
        )
        shot("2-saved-by-keyboard")

        // Unsaved changes: Back asks; Keep editing stays; Discard leaves.
        player.positionMs = 400_000
        key(Key.M)
        key(Key.Escape)
        onNodeWithTag("editor_back_button").performClick()
        waitFor(hasTestTag("editor_discard_button"))
        shot("3-discard-asked")
        onNodeWithTag("editor_keep_editing_button").performClick()
        waitGone(hasTestTag("editor_discard_button"))
        onNodeWithTag("editor_chapter_row_3").performClick() // still editing: the unsaved mark is here
        key(Key.Escape)
        onNodeWithTag("editor_back_button").performClick()
        waitFor(hasTestTag("editor_discard_button"))
        onNodeWithTag("editor_discard_button").performClick()
        waitFor(hasTestTag("browse_list"))
        assertTrue("discarding wrote nothing", !onShare()!!.contains("00:06:40"))

        // Revert deletes the file, after asking.
        onNodeWithText("Heat.1995.mkv").performClick()
        waitFor(hasTestTag("editor_revert_button"))
        onNodeWithTag("editor_revert_button").performClick()
        waitFor(hasTestTag("editor_revert_confirm_button"))
        shot("4-revert-asked")
        onNodeWithTag("editor_revert_confirm_button").performClick()
        waitFor(hasText("Reverted: the chapter file is gone"))
        assertNull(onShare())
        waitGone(hasTestTag("editor_chapter_row_1"))
        waitGone(hasTestTag("editor_revert_button"))
    }
}
