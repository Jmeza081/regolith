package com.regolith.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.regolith.R
import com.regolith.domain.library.ViewMode
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.scaledDp

/** One trailing icon in the top bar: a 44dp hit area around a 19dp glyph in #A0A0A0. */
data class TopBarAction(val icon: Int, val contentDescription: String, val testTag: String, val onClick: () -> Unit)

/**
 * Screen header (design: every tab and pushed screen). Michroma title at
 * 15px, an optional subtitle at 12px underneath, an optional back arrow
 * (20dp, white) and trailing 19dp icons at 44dp hit size (the design drew
 * up to two; the Library's bar has three).
 *
 * Padding follows the design: `12 18 18` with a title alone, `8 18 8`
 * with a subtitle, and the back-arrow variant keeps 18dp side padding
 * with a 12dp gap to the title.
 *
 * Where the actions would leave the title too little room (a wall beside a
 * title's page on the inner display, which is narrower than a phone), the
 * ones that do not fit fold into a More button and its sheet
 * ([topBarActionsShown]), and a title still too long ends in an ellipsis.
 */
@Composable
fun TopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    subtitleMuted: Boolean = false,
    actions: List<TopBarAction> = emptyList(),
    statusBarPadding: Boolean = true,
) {
    var moreOpen by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val shownCount = topBarActionsShown(maxWidth, onBack != null, actions.size)
        TopBarRow(
            title, onBack, subtitle, subtitleMuted, statusBarPadding,
            shown = actions.take(shownCount),
            onMore = if (shownCount < actions.size) ({ moreOpen = true }) else null,
        )
        if (moreOpen && shownCount < actions.size) {
            // The folded actions by name, each closing the sheet before it acts.
            RegolithSheet(title = title.ifEmpty { "More" }, onDismiss = { moreOpen = false }, testTag = "topbar_more_sheet") {
                actions.drop(shownCount).forEach { action ->
                    SheetChoice(action.icon, action.contentDescription, null, action.testTag) {
                        moreOpen = false
                        action.onClick()
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBarRow(
    title: String,
    onBack: (() -> Unit)?,
    subtitle: String?,
    subtitleMuted: Boolean,
    statusBarPadding: Boolean,
    shown: List<TopBarAction>,
    onMore: (() -> Unit)?,
) {
    val colors = RegolithTheme.colors
    val hasSubtitle = subtitle != null
    val hasActions = shown.isNotEmpty() || onMore != null
    Row(
        verticalAlignment = if (hasSubtitle) Alignment.Top else Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (statusBarPadding) Modifier.statusBarsPadding() else Modifier)
            .padding(start = Spacing.s18, end = if (hasActions) Spacing.s8 else Spacing.s18, top = if (hasSubtitle) Spacing.s8 else Spacing.s12, bottom = if (hasSubtitle) Spacing.s8 else Spacing.s18),
    ) {
        if (onBack != null) {
            Box(
                Modifier.size(44.dp).offsetForBack().clickable(interactionSource = null, indication = null, onClick = onBack).testTag("topbar_back_button"),
                contentAlignment = Alignment.CenterStart,
            ) {
                Icon(painterResource(R.drawable.rg_ic_back), contentDescription = "Back", tint = colors.ink, modifier = Modifier.size(20.scaledDp()))
            }
            Spacer(Modifier.width(Spacing.s12))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
            DisplayText(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = TextStyles.subtitle, color = if (subtitleMuted) colors.metadata else colors.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        shown.forEach { action -> TopBarIcon(action.icon, action.contentDescription, action.testTag, action.onClick) }
        if (onMore != null) TopBarIcon(R.drawable.rg_ic_more, "More", "topbar_more_button", onMore)
    }
}

@Composable
private fun TopBarIcon(icon: Int, contentDescription: String, testTag: String, onClick: () -> Unit) {
    Box(
        Modifier.size(TopBarActionSize).clickable(interactionSource = null, indication = null, onClick = onClick).testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = contentDescription, tint = RegolithTheme.colors.body, modifier = Modifier.size(19.scaledDp()))
    }
}

/**
 * How many of [actions] trailing icons a bar [width] wide shows, the rest
 * going into More: all of them while the title keeps [TopBarTitleRoom],
 * otherwise as many as leave it that room beside More, which may be none.
 */
internal fun topBarActionsShown(width: Dp, hasBack: Boolean, actions: Int): Int {
    if (actions <= 1) return actions
    val fixed = Spacing.s18 + Spacing.s8 + if (hasBack) TopBarBackWidth + Spacing.s12 else 0.dp
    fun room(icons: Int) = width - fixed - TopBarActionSize * icons
    if (room(actions) >= TopBarTitleRoom) return actions
    return (actions - 1 downTo 0).firstOrNull { room(it + 1) >= TopBarTitleRoom } ?: 0
}

/** Room for a dozen letters of title: below this the actions start folding into More. */
internal val TopBarTitleRoom = 120.dp

private val TopBarActionSize = 44.dp
private val TopBarBackWidth = 32.dp

/**
 * The grid/rows switch for a media list (Library, Browse). The glyph shows
 * the layout you would get by tapping, which is the convention every file
 * manager uses; the content description names it so screen readers and
 * argent read the action, not the current state.
 */
fun viewModeAction(mode: ViewMode, testTag: String, onToggle: () -> Unit) = TopBarAction(
    icon = if (mode == ViewMode.GRID) R.drawable.rg_ic_view_rows else R.drawable.rg_ic_view_grid,
    contentDescription = if (mode == ViewMode.GRID) "Show as rows" else "Show as grid",
    testTag = testTag,
    onClick = onToggle,
)

/** The back glyph sits flush with the 18dp gutter; the 44dp hit area extends to the right of it. */
private fun Modifier.offsetForBack(): Modifier = this.width(TopBarBackWidth)

/** Filler so a bar without actions keeps the title's baseline where a bar with actions has it. */
@Composable
fun TopBarActionSpace() = Spacer(Modifier.size(44.dp))
