package com.regolith.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles

/**
 * A tappable pill: "1.0×", "A–B", "HW", "Chapters" (design section 10).
 * Outlined on media by default; [selected] fills it red, the one place a
 * pill may carry the accent. Optional leading Lucide icon.
 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    icon: Int? = null,
    onLongClick: (() -> Unit)? = null,
    /** On media: white outline over the picture. Off media: hairline on a surface. */
    onMedia: Boolean = true,
) {
    val colors = RegolithTheme.colors
    val border = when {
        selected -> Color.Transparent
        onMedia -> colors.ink.copy(alpha = 0.55f)
        else -> colors.raised
    }
    val ink = if (selected) colors.inkSoft else colors.ink
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(PillShape)
            .background(if (selected) colors.accent else if (onMedia) colors.ground.copy(alpha = 0.35f) else Color.Transparent)
            .border(1.dp, border, PillShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .height(32.dp)
            .padding(horizontal = Spacing.s12)
            .testTag(testTag),
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Spacing.s4))
        }
        Text(text, style = TextStyles.chip, color = ink)
    }
}
