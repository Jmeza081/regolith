package com.regolith.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.regolith.ui.theme.CardShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles

/**
 * Text input (design section 01, "Forms & controls"): a #0F0F0F card with
 * the label INSIDE it (10px tracked uppercase, #A0A0A0) above the value
 * (500 16/20). Rest is a #1F1F1F hairline; focus a 1.5dp white border;
 * error a 1.5dp red border with the label in red. The caret is red: the
 * accent marks the live position, not the whole field.
 */
@Composable
fun RegolithTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    testTag: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    isError: Boolean = false,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    val colors = RegolithTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor = when {
        isError -> colors.accent
        focused -> colors.ink
        else -> colors.hairline
    }
    val borderWidth = if (isError || focused) 1.5.dp else 1.dp
    Column(
        modifier
            .fillMaxWidth()
            .background(if (enabled) colors.surface else colors.ground, CardShape)
            .border(borderWidth, borderColor, CardShape)
            .padding(Spacing.s12),
    ) {
        Text(label.uppercase(), style = TextStyles.fieldLabel, color = if (isError) colors.accent else colors.body)
        Spacer(Modifier.height(Spacing.s4))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                enabled = enabled,
                interactionSource = interaction,
                textStyle = TextStyles.fieldValue.copy(color = if (enabled) colors.ink else colors.metadata),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                visualTransformation = visualTransformation,
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty() && placeholder != null) {
                            Text(placeholder, style = TextStyles.fieldValue, color = colors.metadata)
                        }
                        inner()
                    }
                },
                modifier = Modifier.weight(1f).testTag(testTag),
            )
            if (trailingIcon != null) {
                Spacer(Modifier.width(Spacing.s8))
                trailingIcon()
            }
        }
    }
}
