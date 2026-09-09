package com.regolith.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles

/**
 * Screen header: back arrow (when the screen was pushed) and a Michroma
 * title, with an optional metadata line underneath. Tabs use it without a
 * back arrow; pushed screens with one.
 */
@Composable
fun TopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    meta: String? = null,
    /** False when the caller already padded for the status bar (e.g. a row with trailing icons). */
    statusBarPadding: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .then(if (statusBarPadding) Modifier.statusBarsPadding() else Modifier)
            .padding(horizontal = if (onBack != null) Spacing.s8 else Spacing.s18, vertical = Spacing.s12),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("topbar_back_button")) {
                Icon(
                    painterResource(LucideR.drawable.lucide_ic_arrow_left),
                    contentDescription = "Back",
                    tint = RegolithTheme.colors.ink,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(Spacing.s4))
        }
        androidx.compose.foundation.layout.Column {
            DisplayText(title, maxLines = 1)
            if (meta != null) {
                androidx.compose.material3.Text(meta, style = TextStyles.metadata, color = RegolithTheme.colors.metadata, maxLines = 1)
            }
        }
    }
}
