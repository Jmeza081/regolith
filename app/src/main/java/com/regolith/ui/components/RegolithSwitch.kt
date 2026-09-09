package com.regolith.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles

/**
 * The design's switch (section 01, "Forms & controls"): a 44×26 pill with
 * a 20dp knob inset 2dp. On is red with a white knob; off a #1F1F1F track
 * with a #6E6E6E knob; disabled sinks to #161616 with a #2E2E2E hairline
 * and a #4A4A4A knob "so the two never read alike".
 */
@Composable
fun SwitchControl(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = RegolithTheme.colors
    val track by animateColorAsState(
        when {
            !enabled -> colors.disabledBg
            checked -> colors.accent
            else -> colors.hairline
        },
        label = "track",
    )
    val knob = when {
        !enabled -> colors.disabledInk
        checked -> Color.White
        else -> colors.metadata
    }
    val x by animateDpAsState(if (checked) 20.dp else 0.dp, label = "knob")
    Box(
        modifier
            .size(width = 44.dp, height = 26.dp)
            .clip(PillShape)
            .background(track)
            .then(if (!enabled) Modifier.border(1.dp, colors.raised, PillShape) else Modifier)
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Switch) { onCheckedChange(!checked) }
            .padding(2.dp)
            .testTag(testTag),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.offset(x = x).size(20.dp).background(knob, PillShape))
    }
}

/**
 * A labelled switch row as Settings and the playback sheet use it: label
 * at 500 15/19, optional note at 400 12/16 in #6E6E6E, switch on the
 * right. 56dp minimum height with 8dp vertical padding.
 */
@Composable
fun RegolithSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    note: String? = null,
) {
    val colors = RegolithTheme.colors
    Row(modifier.fillMaxWidth().padding(vertical = Spacing.s8), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = TextStyles.settingLabel, color = if (enabled) colors.ink else colors.metadata)
            if (note != null) {
                Text(note, style = TextStyles.settingMeta, color = colors.metadata, modifier = Modifier.padding(top = Spacing.s2))
            }
        }
        Spacer(Modifier.width(Spacing.s12))
        SwitchControl(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled, testTag = testTag)
    }
}
