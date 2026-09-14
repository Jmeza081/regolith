package com.regolith.ui.components

import androidx.compose.ui.Modifier

/**
 * Makes the `testTag`s inside a separate window, such as a dialog, visible to
 * UI automation.
 *
 * On Android a dialog is a window of its own, so the root Scaffold's
 * `testTagsAsResourceId` does not reach it, and uiautomator (what argent's
 * `describe` reads) would find no ids on its buttons. The desktop has no such
 * setting: its UI tests read test tags directly, so there this does nothing.
 */
internal expect fun Modifier.exposeTestTags(): Modifier
