package com.regolith.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import com.regolith.R
import com.regolith.domain.library.ViewMode

/**
 * The grid/rows switch for a media list (Library, Browse). The glyph shows
 * the layout you would get by tapping, which is the convention every file
 * manager uses; the content description names it so screen readers and
 * argent read the action, not the current state.
 *
 * Stays in the phone app while [TopBar] is shared: it uses the phone's
 * drawables and the library's [ViewMode].
 */
@Composable
fun viewModeAction(mode: ViewMode, testTag: String, onToggle: () -> Unit) = TopBarAction(
    icon = painterResource(if (mode == ViewMode.GRID) R.drawable.rg_ic_view_rows else R.drawable.rg_ic_view_grid),
    contentDescription = if (mode == ViewMode.GRID) "Show as rows" else "Show as grid",
    testTag = testTag,
    onClick = onToggle,
)
