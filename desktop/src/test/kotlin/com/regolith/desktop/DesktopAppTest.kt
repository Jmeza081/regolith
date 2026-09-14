package com.regolith.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.regolith.desktop.ui.RegolithChaptersApp
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.Socket
import javax.imageio.ImageIO

/**
 * The whole app against the local Samba fixture (localhost:1445, share
 * `media`, `~/RegolithShare`): connect → Films → open the 5-minute test film
 * → rename its first chapter → Save → the file on the share holds the edit.
 *
 * Real SMB and real libvlc; only the picture is a grey box, because the
 * in-memory test renderer cannot host VLC's Swing view. Skipped when the
 * fixture is not running. The fixture's chapter file is restored afterwards.
 * PNGs of each step land in build/test-shots.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopAppTest {
    private val sidecar = File(System.getProperty("user.home"), "RegolithShare/Films/Long.Test.2026.chapters.txt")
    private val shots = File("build/test-shots").apply { mkdirs() }

    @Test
    fun connectBrowseEditSave() {
        assumeTrue("Samba fixture on localhost:1445 is not running", runCatching { Socket("localhost", 1445).close() }.isSuccess)
        val before = sidecar.takeIf { it.exists() }?.readBytes()
        try {
            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { RegolithChaptersApp(AppGraph(), videoSurface = { Box(Modifier.fillMaxSize().background(Color(0xFF222222))) }) }
                fun shot(name: String) = ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(shots, "$name.png"))
                fun waitFor(timeoutMs: Long = 20_000, matcher: androidx.compose.ui.test.SemanticsMatcher) =
                    waitUntil(timeoutMillis = timeoutMs) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

                onNodeWithTag("servers_address_field").performTextInput("smb://localhost:1445/media")
                shot("1-servers")
                onNodeWithTag("servers_connect_button").performClick()
                waitFor(matcher = hasTestTag("browse_list"))
                shot("2-browse-root")

                onNodeWithText("Films").performClick()
                waitFor(matcher = hasText("Long.Test.2026.mp4"))
                shot("3-browse-films")

                onNodeWithText("Long.Test.2026.mp4").performClick()
                waitFor(matcher = hasTestTag("editor_chapter_list"))
                waitFor(30_000, hasText("/ 5:00", substring = true))
                shot("4-editor")

                onNodeWithTag("editor_chapter_row_0").performClick()
                onNodeWithTag("editor_name_field").performTextReplacement("Cold open")
                onNodeWithTag("editor_preview").assertTextContains("CHAPTER01NAME=Cold open", substring = true)
                shot("5-renamed")

                onNodeWithTag("editor_save_button").performClick()
                waitFor(matcher = hasText("Saved to the share"))
                shot("6-saved")
            }
            val onShare = sidecar.readText()
            check("CHAPTER01NAME=Cold open" in onShare) { "the chapter file on the share does not hold the edit:\n$onShare" }
        } finally {
            if (before != null) sidecar.writeBytes(before) else sidecar.delete()
        }
    }
}
