package com.regolith.desktop

import androidx.compose.runtime.remember
import com.regolith.data.smb.JcifsGateway
import com.regolith.domain.smb.SmbCredentials
import java.io.File
import kotlin.system.exitProcess
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.regolith.desktop.player.NativeVlc
import com.regolith.desktop.ui.LeaveGuard
import com.regolith.desktop.ui.RegolithChaptersApp

/**
 * Entry point. `application {}` is the process; `Window` is the desktop's
 * Activity: one per open window, and closing the last one ends the app.
 *
 * `--self-check smb://host/share/film.mp4 [frame.png]` skips the window and
 * runs [SelfCheck] instead, exiting 0 when the film played and seeked.
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "--self-check") exitProcess(selfCheck(args.drop(1)))
    // libvlc is found once, before any player exists; see NativeVlc.
    NativeVlc.load()
    app()
}

private fun selfCheck(args: List<String>): Int = try {
    val (host, share, path) = SelfCheck.parse(args.getOrElse(0) { error("--self-check needs an smb:// URL of a film") })
    val report = SelfCheck.play(JcifsGateway(), host, SmbCredentials.Guest, share, path, frameOut = args.getOrNull(1)?.let(::File))
    println("libvlc from: ${report.libvlc}")
    println("length: ${report.lengthMs} ms; seek to 4:00 landed at ${report.landedMs} ms")
    report.frame?.let { println("frame: ${it.absolutePath}") }
    if (report.lengthMs > 0 && report.landedMs > 0) 0 else 1
} catch (e: Throwable) {
    System.err.println("SELF-CHECK FAILED: $e")
    1
}

private fun app() = application {
    val graph = remember { AppGraph() }
    // Closing the window with unsaved chapters asks first, exactly as Back does.
    val guard = remember { LeaveGuard() }
    Window(
        onCloseRequest = { guard.request(::exitApplication) },
        title = "Regolith Chapters",
        state = rememberWindowState(width = 1280.dp, height = 800.dp),
    ) {
        RegolithChaptersApp(graph, guard)
    }
}
