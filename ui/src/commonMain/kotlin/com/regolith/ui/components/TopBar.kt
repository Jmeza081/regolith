package com.regolith.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.scaledDp

/**
 * One trailing icon in the top bar: a 44dp hit area around a 19dp glyph in #A0A0A0.
 * The phone passes `painterResource(R.drawable.…)` as [icon].
 */
data class TopBarAction(val icon: Painter, val contentDescription: String, val testTag: String, val onClick: () -> Unit)

/**
 * Screen header (design: every tab and pushed screen). Michroma title at
 * 15px, an optional subtitle at 12px underneath, an optional back arrow
 * (20dp, white) and up to two trailing 19dp icons at 44dp hit size.
 *
 * Padding follows the design: `12 18 18` with a title alone, `8 18 8`
 * with a subtitle, and the back-arrow variant keeps 18dp side padding
 * with a 12dp gap to the title.
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
    val colors = RegolithTheme.colors
    val hasSubtitle = subtitle != null
    Row(
        verticalAlignment = if (hasSubtitle) Alignment.Top else Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .then(if (statusBarPadding) Modifier.statusBarsPadding() else Modifier)
            .padding(start = Spacing.s18, end = if (actions.isEmpty()) Spacing.s18 else Spacing.s8, top = if (hasSubtitle) Spacing.s8 else Spacing.s12, bottom = if (hasSubtitle) Spacing.s8 else Spacing.s18),
    ) {
        if (onBack != null) {
            Box(
                Modifier.size(44.dp).offsetForBack().clickable(interactionSource = null, indication = null, onClick = onBack).testTag("topbar_back_button"),
                contentAlignment = Alignment.CenterStart,
            ) {
                Icon(RegolithIcons.Back, contentDescription = "Back", tint = colors.ink, modifier = Modifier.size(20.scaledDp()))
            }
            Spacer(Modifier.width(Spacing.s12))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
            DisplayText(title, maxLines = 1)
            if (subtitle != null) {
                Text(subtitle, style = TextStyles.subtitle, color = if (subtitleMuted) colors.metadata else colors.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        actions.forEach { action ->
            Box(
                Modifier.size(44.dp).clickable(interactionSource = null, indication = null, onClick = action.onClick).testTag(action.testTag),
                contentAlignment = Alignment.Center,
            ) {
                Icon(action.icon, contentDescription = action.contentDescription, tint = colors.body, modifier = Modifier.size(19.scaledDp()))
            }
        }
    }
}

/** The back glyph sits flush with the 18dp gutter; the 44dp hit area extends to the right of it. */
private fun Modifier.offsetForBack(): Modifier = this.width(32.dp)

/** Filler so a bar without actions keeps the title's baseline where a bar with actions has it. */
@Composable
fun TopBarActionSpace() = Spacer(Modifier.size(44.dp))
