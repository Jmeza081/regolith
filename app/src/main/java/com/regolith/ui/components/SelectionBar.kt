package com.regolith.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.scaledDp

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
 * How many videos a selection comes to, and what it will cost.
 *
 * Floats above the nav pill on a phone and at the foot of the pane on a
 * wide window, because the caller positions it with
 * [com.regolith.ui.components.LocalNavPillInsets] rather than a literal.
 *
 * @param summary the two facts that never change shape: "42 videos · 4.3 GB",
 *   or "Counting…" while a picked folder has yet to be walked.
 * @param detail whatever qualifies them — already here, not listed yet, no
 *   room — or null when nothing does.
 * @param actionEnabled false draws the disabled fill rather than the accent.
 *   Download only goes red when tapping it would actually work.
 */
@Composable
fun SelectionBar(
    summary: String,
    onAction: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    actionText: String = "Download",
    actionEnabled: Boolean = true,
    testTag: String = "browse_select_bar",
) {
    val colors = RegolithTheme.colors
    SurfaceCard(modifier = modifier.testTag(testTag), contentPadding = PaddingValues(Spacing.s12)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    summary,
                    style = TextStyles.notice,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("${testTag}_summary"),
                )
                if (detail != null) {
                    Text(
                        detail,
                        style = TextStyles.meta,
                        color = colors.metadata,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("${testTag}_detail"),
                    )
                }
            }
            Spacer(Modifier.width(Spacing.s8))
            TertiaryButton(
                text = "Cancel",
                onClick = onCancel,
                testTag = "${testTag}_cancel",
            )
            Spacer(Modifier.width(Spacing.s4))
            PrimaryButton(
                text = actionText,
                onClick = onAction,
                testTag = "${testTag}_download",
                compact = true,
                enabled = actionEnabled,
            )
        }
    }
}

/**
 * How much room the bar needs above whatever it floats over, so a list can
 * grow its bottom content padding and its last row stays reachable.
 */
val SELECTION_BAR_HEIGHT: Dp = 72.scaledDp()
