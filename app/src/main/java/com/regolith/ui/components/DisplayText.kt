package com.regolith.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.TextStyles

/**
 * Michroma display text: screen titles, eyebrows, the wordmark.
 *
 * Two design rules are enforced here so no screen has to remember them:
 * 1. Michroma is uppercase only. The text is uppercased on the way in.
 * 2. Michroma is stroked 0.55px for weight. Compose has no `paint-order`, so
 *    this draws the text twice: a thin stroke pass under a fill pass.
 *
 * Long titles break Michroma's rhythm (the design notes "Chungking Express");
 * callers with unbounded strings should prefer [TextStyles.rowLabel].
 */
@Composable
fun DisplayText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyles.screenTitle,
    color: Color = RegolithTheme.colors.ink,
    maxLines: Int = Int.MAX_VALUE,
) {
    val upper = text.uppercase()
    Box(modifier) {
        Text(
            text = upper,
            style = style.copy(drawStyle = Stroke(width = 0.55f)),
            color = color,
            maxLines = maxLines,
        )
        Text(
            text = upper,
            style = style,
            color = color,
            maxLines = maxLines,
        )
    }
}

/** Section eyebrow, e.g. "ON THIS NETWORK". */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    DisplayText(text, modifier, style = TextStyles.eyebrow, color = RegolithTheme.colors.metadata, maxLines = 1)
}
