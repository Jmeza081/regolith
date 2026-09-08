package com.regolith.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles

/*
 * Buttons (design section 01). The rule that matters: red fills the ONE
 * action a screen wants. Secondary is an outlined pill, tertiary is plain
 * white text with no underline. Never a second red fill on a screen.
 *
 * Every button takes a `testTag` so argent/uiautomator can find it by a
 * stable id (like `data-testid`). Convention: `feature_element`,
 * e.g. "addserver_connect_button".
 */

private val ButtonHeight = 48.dp
private val ButtonPadding = PaddingValues(horizontal = Spacing.s18)

/** Red fill. One per screen. Optional leading icon (e.g. play for "Resume"). */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: Painter? = null,
) {
    val colors = RegolithTheme.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = PillShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.accent,
            contentColor = colors.inkSoft,
            disabledContainerColor = colors.hairline,
            disabledContentColor = colors.metadata,
        ),
        contentPadding = ButtonPadding,
        modifier = modifier.height(ButtonHeight).testTag(testTag),
    ) {
        ButtonContent(text, leadingIcon)
    }
}

/** Outlined pill, white ink on a hairline border. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: Painter? = null,
) {
    val colors = RegolithTheme.colors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = PillShape,
        border = BorderStroke(1.dp, if (enabled) colors.raised else colors.hairline),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = colors.ink,
            disabledContentColor = colors.metadata,
        ),
        contentPadding = ButtonPadding,
        modifier = modifier.height(ButtonHeight).testTag(testTag),
    ) {
        ButtonContent(text, leadingIcon)
    }
}

/** Plain white text, no underline, no border. */
@Composable
fun TertiaryButton(
    text: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = RegolithTheme.colors
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = PillShape,
        colors = ButtonDefaults.textButtonColors(
            contentColor = colors.ink,
            disabledContentColor = colors.metadata,
        ),
        contentPadding = ButtonPadding,
        modifier = modifier.height(ButtonHeight).testTag(testTag),
    ) {
        Text(text, style = TextStyles.label)
    }
}

/**
 * Destructive: red text inside the outlined pill. Used for "Disconnect".
 * It is the same red as primary, which the design flags as the one cost of
 * this direction; keep it rare.
 */
@Composable
fun DestructiveButton(
    text: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = RegolithTheme.colors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = PillShape,
        border = BorderStroke(1.dp, colors.raised),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = colors.accent,
            disabledContentColor = colors.metadata,
        ),
        contentPadding = ButtonPadding,
        modifier = modifier.height(ButtonHeight).testTag(testTag),
    ) {
        Text(text, style = TextStyles.label)
    }
}

@Composable
private fun ButtonContent(text: String, leadingIcon: Painter?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, modifier = Modifier.width(18.dp).height(18.dp))
            Spacer(Modifier.width(Spacing.s8))
        }
        Text(text, style = TextStyles.label)
    }
}
