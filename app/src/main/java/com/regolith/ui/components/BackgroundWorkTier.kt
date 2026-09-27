package com.regolith.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.regolith.domain.library.BackgroundWork
import com.regolith.domain.library.TierTarget
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import dev.chrisbanes.haze.HazeState

/**
 * "Reading 2 shares · 1,204 files", docked above the nav pill, in the
 * pill's own glass — a tier of the chrome, beside the selection summary
 * and the message capsule ([ChromeMessageHost]).
 *
 * **Why it is app-level and not a line on a screen.** A scan is the app's
 * state, not a screen's: it changes what Library shows, what Browse can
 * count and what Search can find, and it outlives whichever tab happened to
 * start it. Home, Library and Search each grew their own sentence about it,
 * which meant the answer to "is it still going?" depended on where you were
 * standing. Drawn once by the nav graph, above `NavDisplay`, it survives
 * every navigation because it is not inside one.
 *
 * Phones only, like the message tier: a vertical rail has no "above" to
 * dock a full-width tier to.
 *
 * @param work what is running, or null for nothing — the tier animates
 *   itself away rather than the caller removing it, so the chrome does not
 *   jump.
 * @param onOpen where a tap goes, for work that has a [BackgroundWork.target]
 *   (uploads: the folder they are going to). Work without one — a scan —
 *   is not a way anywhere and does not take the tap.
 */
@Composable
fun BackgroundWorkTier(
    work: BackgroundWork?,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    onOpen: ((TierTarget) -> Unit)? = null,
) {
    val colors = RegolithTheme.colors
    AnimatedVisibility(
        visible = work != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        // Held so the last line does not vanish mid-fade as the work ends.
        val shown = rememberLast(work)
        val target = shown?.target
        Box(
            Modifier.fillMaxWidth()
                .navChromeFrost(hazeState, ChromeTierShape)
                .then(
                    if (target != null && onOpen != null) {
                        Modifier.clickable(interactionSource = null, indication = null, onClickLabel = "Show") { onOpen(target) }
                    } else {
                        Modifier
                    },
                )
                .testTag("chrome_background_work"),
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .padding(horizontal = Spacing.s18, vertical = Spacing.s12),
            ) {
                Text(
                    shown?.line.orEmpty(),
                    style = TextStyles.rowLabelMedium,
                    color = colors.ink,
                    modifier = Modifier.testTag("chrome_background_work_line"),
                )
                Box(Modifier.padding(top = Spacing.s8)) {
                    // Thinner than the Scanning screen's hero bar: this sits
                    // under a line of text rather than under a 40pt number.
                    val fraction = shown?.fraction
                    // Paused holds the bar where it stopped, greyed: nothing
                    // is moving, and a red bar would say something is.
                    if (fraction == null) {
                        SweepBar(height = 3.dp)
                    } else {
                        ProgressBar(fraction = fraction, height = 3.dp, color = if (shown.paused) colors.metadata else null)
                    }
                }
            }
        }
    }
}

/**
 * The last non-null value seen.
 *
 * [AnimatedVisibility] keeps its content composed for the length of the
 * exit animation, so without this the tier would draw one frame of empty
 * text on its way out.
 */
@Composable
private fun rememberLast(work: BackgroundWork?): BackgroundWork? {
    val holder = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(work) }
    if (work != null) holder.value = work
    return holder.value
}
