package com.regolith.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles

/**
 * One row in a list: optional Lucide icon, a 15px semibold label, 12px
 * metadata, and a trailing chevron (navigates) or check (selected).
 * Rows are separated by a hairline, never by cards.
 */
@Composable
fun ListRow(
    title: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    icon: Int? = null,
    trailing: RowTrailing = RowTrailing.Chevron,
    divider: Boolean = true,
) {
    val colors = RegolithTheme.colors
    Column(modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = Spacing.s12)
                .testTag(testTag),
        ) {
            if (icon != null) {
                Icon(painterResource(icon), contentDescription = null, tint = colors.body, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(Spacing.s12))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = TextStyles.rowLabel, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (meta != null) {
                    Text(meta, style = TextStyles.metadata, color = colors.metadata, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(Spacing.s8))
            when (trailing) {
                RowTrailing.Chevron -> Icon(
                    painterResource(LucideR.drawable.lucide_ic_chevron_right),
                    contentDescription = null,
                    tint = colors.metadata,
                    modifier = Modifier.size(20.dp),
                )
                RowTrailing.Checked -> Icon(
                    painterResource(LucideR.drawable.lucide_ic_check),
                    contentDescription = "Selected",
                    tint = colors.accent,
                    modifier = Modifier.size(20.dp),
                )
                RowTrailing.None -> Unit
            }
        }
        if (divider) HorizontalDivider(color = colors.hairline, thickness = 1.dp)
    }
}

enum class RowTrailing { Chevron, Checked, None }
