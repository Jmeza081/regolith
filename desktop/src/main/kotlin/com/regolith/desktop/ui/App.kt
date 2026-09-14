package com.regolith.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import com.regolith.desktop.AppGraph
import com.regolith.desktop.navigation.Route
import com.regolith.desktop.player.FilmPlayer
import com.regolith.desktop.player.NativeVlc
import com.regolith.desktop.player.UnavailablePlayer
import com.regolith.desktop.player.VlcPlayer
import com.regolith.desktop.ui.browse.BrowseScreen
import com.regolith.desktop.ui.editor.EditorScreen
import com.regolith.desktop.ui.servers.ServersScreen
import com.regolith.ui.theme.RegolithTheme

/**
 * The whole window: theme, back stack, and which screen the top entry draws.
 *
 * The back stack is a list the app owns, like the phone's Navigation 3
 * stack: forward pushes, Back pops. `key(route)` gives each entry a fresh
 * composition, so a screen's state holder and coroutine scope live exactly
 * as long as the entry is on top.
 *
 * @param guard asked before Back or closing the window; Main passes the one the
 *   window's close button uses.
 * @param newPlayer builds the one player the window uses; tests can pass a fake.
 * @param videoSurface draws the player's picture. The default hosts VLC's
 *   Swing view; Compose's in-memory test renderer cannot host Swing, so UI
 *   tests pass a plain box.
 */
@Composable
fun RegolithChaptersApp(
    graph: AppGraph,
    guard: LeaveGuard = remember { LeaveGuard() },
    newPlayer: () -> FilmPlayer = ::defaultPlayer,
    videoSurface: @Composable (FilmPlayer) -> Unit = { p ->
        (p as? VlcPlayer)?.let { vlc -> SwingPanel(factory = { vlc.surface }, modifier = Modifier.fillMaxSize()) }
    },
) {
    val player = remember { newPlayer() }
    DisposableEffect(Unit) { onDispose { (player as? VlcPlayer)?.dispose() ?: player.release() } }
    val backStack = remember { mutableStateListOf<Route>(Route.Servers) }
    fun back() = guard.request {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    RegolithTheme {
        Surface(Modifier.fillMaxSize(), color = RegolithTheme.colors.ground, contentColor = RegolithTheme.colors.ink) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                val top = backStack.last()
                key(top) {
                    when (top) {
                        Route.Servers -> ServersScreen(graph, onConnected = { backStack.add(Route.Browse(it)) })
                        is Route.Browse -> BrowseScreen(
                            graph, top,
                            onBack = ::back,
                            onOpenFolder = { folder -> backStack.add(Route.Browse(top.connection, folder)) },
                            onOpenFilm = { film -> backStack.add(Route.Editor(top.connection, top.folder, film)) },
                        )
                        is Route.Editor -> EditorScreen(graph, top, player, videoSurface, guard, onBack = ::back)
                    }
                }
            }
        }
    }
}

/** VLC when libvlc loads; otherwise a player that says why, so the editor still works. */
private fun defaultPlayer(): FilmPlayer =
    if (NativeVlc.load() == null) UnavailablePlayer()
    else runCatching<FilmPlayer> { VlcPlayer() }.getOrElse { UnavailablePlayer("Video is unavailable: ${it.message}") }
