package com.regolith.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import dev.chrisbanes.haze.HazeState

/**
 * The hold that opens a multi-selection.
 *
 * The platform's own long-press timeout, which `combinedClickable` uses
 * without being asked, is ~500 ms — the reflex Gmail, Photos, Files and
 * every file manager have trained. A longer hold was considered and
 * rejected: at two seconds, with nothing moving under the finger, the row
 * reads as dead and people let go around 800 ms.
 *
 * Nothing reads this constant, because at the platform value nothing has
 * to — `combinedClickable` already uses it. It is here so the number has a
 * home and the decision is written down. To change it, provide a
 * `LocalViewConfiguration` whose `longPressTimeoutMillis` returns this
 * value around the list: `combinedClickable` reads the timeout from there,
 * so the ripple, the accessibility action and the semantics all survive,
 * where a hand-rolled `detectTapGestures` would drop them.
 */
const val SELECT_HOLD_MS: Long = 500L

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
 * The pill itself draws only the verbs and the way out. The count and its
 * qualifier ride in [SelectionSummaryTier] directly above it, because the
 * pill is five cells wide and has nowhere to put a sentence — and a picked
 * batch that will not fit has to be able to SAY so.
 */
data class SelectionChromeState(
    val verbs: List<SelectionVerb>,
    val onCancel: () -> Unit,
    /** "4 videos · 25.2 GB": what is picked and what it costs. */
    val summary: String,
    /** Whatever qualifies that — no room, already here, why a verb is off. */
    val detail: String? = null,
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

/**
 * What is picked, docked above the nav chrome while a selection is running.
 *
 * This is the line the old floating `SelectionBar` carried, in the chrome's
 * own glass and in the same slot a message uses. It exists because the count
 * is not decoration: "Not enough room — free 3.1 GB more" is the reason the
 * Download verb is grey, and a greyed verb with no explanation is a dead end.
 *
 * Drawn once by the nav graph. Screens never place it.
 */
@Composable
fun SelectionSummaryTier(state: SelectionChromeState, hazeState: HazeState, modifier: Modifier = Modifier) {
    val colors = RegolithTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .navChromeFrost(hazeState, SelectionTierShape)
            .padding(horizontal = Spacing.s18, vertical = Spacing.s12)
            .testTag("selection_summary"),
    ) {
        Text(
            state.summary,
            style = TextStyles.rowLabelMedium,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("selection_summary_text"),
        )
        state.detail?.let {
            Text(
                it,
                style = TextStyles.meta,
                color = colors.metadata,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("selection_summary_detail"),
            )
        }
    }
}

/** The tier's corners: the chrome family, as the message tier uses. */
private val SelectionTierShape = RoundedCornerShape(26.dp)
