package com.regolith.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
 * An error message on the 8%-red-tinted card. The tint keeps the message
 * readable and lets the retry button underneath keep its weight; a full
 * red fill would fight it.
 */
@Composable
fun ErrorCard(
    message: String,
    modifier: Modifier = Modifier,
    testTag: String = "error_card",
    /** Technical small print (status codes) shown under the message. */
    detail: String? = null,
) {
    val colors = RegolithTheme.colors
    SurfaceCard(modifier = modifier.testTag(testTag), style = CardStyle.Error) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                painterResource(LucideR.drawable.lucide_ic_triangle_alert),
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Spacing.s12))
            Column {
                Text(message, style = TextStyles.body, color = colors.ink)
                if (detail != null) {
                    Text(detail, style = TextStyles.metadata, color = colors.metadata, modifier = Modifier.padding(top = Spacing.s4).testTag("${testTag}_detail"))
                }
            }
        }
    }
}
