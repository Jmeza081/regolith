package com.regolith.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.VisualTransformation
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Text input (design section 01, "Forms & controls"). Label sits above the
 * field in metadata grey. Focus is a white border, never a glow; the caret
 * is red because the accent marks the live position, not the whole field.
 * Error is a red border; put the message in an [ErrorCard] above the form.
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
    Column(modifier) {
        Text(
            text = label,
            style = TextStyles.metadata,
            color = colors.metadata,
            modifier = Modifier.padding(bottom = Spacing.s4),
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            enabled = enabled,
            isError = isError,
            textStyle = TextStyles.body.copy(color = colors.ink),
            placeholder = placeholder?.let { { Text(it, style = TextStyles.body, color = colors.metadata) } },
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            trailingIcon = trailingIcon,
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = colors.surface,
                unfocusedContainerColor = colors.surface,
                errorContainerColor = colors.surface,
                disabledContainerColor = colors.ground,
                focusedBorderColor = colors.ink,
                unfocusedBorderColor = colors.hairline,
                errorBorderColor = colors.accent,
                disabledBorderColor = colors.hairline,
                cursorColor = colors.accent,
                errorCursorColor = colors.accent,
                focusedTextColor = colors.ink,
                unfocusedTextColor = colors.ink,
                disabledTextColor = colors.metadata,
            ),
            modifier = Modifier.fillMaxWidth().testTag(testTag),
        )
    }
}
