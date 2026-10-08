package com.regolith.ui.components

import android.view.View
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Keeps the screen awake for as long as this is on screen, as a playing
 * film does: a story is watched, not touched. An invisible view of its own
 * carries the flag, so it goes with this and never clears anyone else's
 * (the window stays awake while any view in it asks).
 */
@Composable
fun KeepScreenOn() {
    AndroidView(factory = { View(it).apply { keepScreenOn = true } }, modifier = Modifier.size(0.dp))
}
