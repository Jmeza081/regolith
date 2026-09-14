package com.regolith.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.regolith.desktop.ui.LeaveGuard
import com.regolith.desktop.ui.RegolithChaptersApp

/**
 * Entry point. `application {}` is the process; `Window` is the desktop's
 * Activity: one per open window, and closing the last one ends the app.
 */
fun main() = application {
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
