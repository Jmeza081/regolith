package com.regolith.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.TYPE_SCALE
import com.regolith.ui.theme.TextStyles

/**
 * Michroma display text: screen titles, the wordmark, dialog titles.
 *
 * Two design rules are enforced here so no screen has to remember them:
 * 1. Michroma is uppercase only. The text is uppercased on the way in.
 * 2. Michroma is stroked 0.55px for weight (`.dh` in the design). Compose
 *    has no `paint-order`, so this draws the text twice: a thin stroke
 *    pass under a fill pass. The stroke is scaled with the type
 *    ([com.regolith.ui.theme.TYPE_SCALE]) so the glyphs keep the design's
 *    optical weight at the larger size.
 */
@Composable
fun DisplayText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyles.screenTitle,
    color: Color = RegolithTheme.colors.ink,
    maxLines: Int = Int.MAX_VALUE,
    textAlign: TextAlign? = null,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val upper = text.uppercase()
    Box(modifier) {
        Text(text = upper, style = style.copy(drawStyle = Stroke(width = 0.55f * TYPE_SCALE)), color = color, maxLines = maxLines, textAlign = textAlign, overflow = overflow)
        Text(text = upper, style = style, color = color, maxLines = maxLines, textAlign = textAlign, overflow = overflow)
    }
}

/**
 * Section eyebrow ("CONTINUE WATCHING", "3 FOLDERS"): Space Grotesk 600
 * 11px, tracked .14em, uppercase. #A0A0A0 by default; Browse and Search
 * use #6E6E6E ([muted]).
 */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, muted: Boolean = false, large: Boolean = false) {
    val colors = RegolithTheme.colors
    Text(
        text.uppercase(),
        // [large] names a whole section rather than labelling one thing: the
        // rows on Home, where the 11px eyebrow sat quieter than the metadata
        // next to it.
        style = if (large) TextStyles.sectionTitle else TextStyles.eyebrow,
        color = if (muted) colors.metadata else colors.body,
        modifier = modifier,
        maxLines = 1,
    )
}

