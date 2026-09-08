package com.regolith.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.TextStyles

/**
 * Labelled on/off switch. On is red (the design accepts that red reads as
 * both "on" and "destructive"); off is a light knob on a mid track.
 */
@Composable
fun RegolithSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = RegolithTheme.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = TextStyles.rowLabel, color = if (enabled) colors.ink else colors.metadata, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.inkSoft,
                checkedTrackColor = colors.accent,
                uncheckedThumbColor = colors.body,
                uncheckedTrackColor = colors.raised,
                uncheckedBorderColor = colors.raised,
                disabledCheckedTrackColor = colors.hairline,
                disabledUncheckedTrackColor = colors.hairline,
            ),
            modifier = Modifier.testTag(testTag),
        )
    }
}
