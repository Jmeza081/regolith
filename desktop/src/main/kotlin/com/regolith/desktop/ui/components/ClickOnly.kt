package com.regolith.desktop.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties

/**
 * A control that a mouse click does not focus, as buttons behave on macOS.
 *
 * The Mac's controls are the shared ones in `:ui`. Pass this as their
 * `modifier`: they apply the caller's modifier before their own click
 * handling, so the click target stays unfocusable. Without it, clicking Mark
 * would park focus on it and the next Space would press Mark again instead
 * of reaching the editor's shortcuts.
 */
fun Modifier.clickOnly(): Modifier = focusProperties { canFocus = false }
