package com.regolith.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * One verb the pill offers while something is selected — a cell in the nav
 * pill, drawn to the same anatomy as a tab: a 19dp glyph over a tracked
 * label.
 *
 * A [destructive] verb accents its GLYPH rather than taking a red fill: the
 * screen gets one red, and it belongs to the confirm dialog where the
 * decision is actually taken.
 */
data class SelectionVerb(
    val label: String,
    /** A drawable res id, e.g. `R.drawable.rg_ic_trash`. */
    val icon: Int,
    val onClick: () -> Unit,
    val testTag: String,
    /** False greys the cell out; the screen's top bar should say why. */
    val enabled: Boolean = true,
    val destructive: Boolean = false,
)

/**
 * What the nav pill shows INSTEAD of its tabs while something is selected.
 *
 * Only what the pill can actually draw: a way out, and the verbs. The count
 * is not here because the pill has no room for it — the screen's own top bar
 * already says "2 selected", which is where a count belongs.
 */
data class SelectionChromeState(
    val verbs: List<SelectionVerb>,
    val onCancel: () -> Unit,
)

/**
 * The channel a screen uses to turn the nav pill into its toolbar.
 *
 * **Why the screen pushes and the chrome pulls.** The pill is drawn once, by
 * the nav graph, which knows nothing about files. The dialogs, the repository
 * and the undo all live in the screen. So the screen keeps the behaviour and
 * lends the chrome its buttons, rather than the chrome growing opinions about
 * what a selection is for. A screen with nothing to offer simply never calls
 * [show], and the pill stays a nav pill.
 *
 * **Why the tabs go away rather than sharing the space.** Selecting something
 * is the task; going somewhere else abandons it. Leaving the tabs up would
 * offer a way to walk out of the job mid-sentence, and the pill cannot hold
 * five tabs and five verbs at once anyway.
 */
@Stable
class SelectionChrome {
    var state: SelectionChromeState? by mutableStateOf(null)
        private set

    fun show(next: SelectionChromeState) {
        state = next
    }

    fun clear() {
        state = null
    }
}

/** Provided by the nav graph. A screen composed without it lends its buttons to nothing. */
val LocalSelectionChrome = staticCompositionLocalOf { SelectionChrome() }
