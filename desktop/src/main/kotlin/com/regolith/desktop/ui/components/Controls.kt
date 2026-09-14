package com.regolith.desktop.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.regolith.desktop.ui.Problem
import com.regolith.desktop.ui.theme.Palette

/*
 * The Mac app's handful of controls, styled from the phone's tokens.
 *
 * These are deliberately few and small: the phone's `ui/components` cannot be
 * used from here yet (they load Android resources), and sharing them is the
 * next branch. When that lands, screens swap these for the shared ones.
 */

private val PillShape = RoundedCornerShape(percent = 50)

/**
 * A control that a mouse click does not focus, as buttons behave on macOS.
 * Without this, clicking Mark would park focus on it and the next Space
 * would press Mark again instead of reaching the editor's shortcuts.
 */
fun Modifier.clickOnly(): Modifier = focusProperties { canFocus = false }

/** The one red action on a screen. [loading] shows a ring and ignores clicks. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Button(
        onClick = { if (!loading) onClick() },
        enabled = enabled,
        shape = PillShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Palette.Red,
            contentColor = Palette.InkSoft,
            disabledContainerColor = Palette.DisabledBg,
            disabledContentColor = Palette.DisabledInk,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp),
        modifier = modifier.clickOnly().height(40.dp),
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(16.dp), color = Palette.InkSoft, strokeWidth = 2.dp)
        else Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Every other action: frosted outline, no fill. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = PillShape,
        border = BorderStroke(1.dp, if (enabled) Palette.FrostBorder else Palette.Hairline),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Palette.FrostBg,
            contentColor = Palette.Ink,
            disabledContentColor = Palette.DisabledInk,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = modifier.clickOnly().height(36.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
    }
}

/**
 * A text-only action that should not compete with the buttons: Back, Done.
 * Pass [color] = [Palette.Red] for the destructive ones (Revert, Remove all),
 * as the phone draws them.
 */
@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Palette.Body) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.clickOnly()) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) color else Palette.DisabledInk, maxLines = 1, softWrap = false)
    }
}

/** One labelled field. [password] hides the text. */
@Composable
fun ChaptersTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    password: Boolean = false,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = Palette.Metadata) } },
        singleLine = true,
        enabled = enabled,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Palette.Ink,
            unfocusedBorderColor = Palette.Raised,
            focusedLabelColor = Palette.Ink,
            unfocusedLabelColor = Palette.Body,
            cursorColor = Palette.Ink,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** An uppercase, letter-spaced label: section names and paths. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.Metadata, modifier = modifier)
}

/** A [Problem] on the phone's 8%-red card: the sentence, then the small print. */
@Composable
fun ProblemCard(problem: Problem, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Palette.RedTint, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(problem.message, style = MaterialTheme.typography.bodyLarge, color = Palette.Ink)
        problem.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Metadata) }
    }
}
