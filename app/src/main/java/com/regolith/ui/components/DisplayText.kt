package com.regolith.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.TYPE_SCALE
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.designSp

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
 * 11px, tracked .14em, uppercase. #A0A0A0 by default; Browse uses #6E6E6E
 * ([muted]), as does Search for anything that labels one thing.
 *
 * [large] is 13px at .12em — tracking eases off as the size grows, which is
 * how tracked caps stay even. Use it where the label names a GROUP that
 * something else could follow: Home's rows, and Search's two result groups,
 * where the label is the only thing saying one wall has ended.
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

/**
 * An [Eyebrow] with its section's one action at the right: "Clear failed",
 * "Try again", "Cancel all". Text rather than a button because it belongs
 * to the label — it acts on everything the eyebrow names, and a button there
 * would read as a second, competing section control.
 *
 * @param actionColor the accent for an act that removes things (Library's
 *   clears), ink for one that carries on (an upload's Try again), the body
 *   grey for one that merely tidies.
 */
@Composable
fun EyebrowAction(
    text: String,
    action: String,
    onAction: () -> Unit,
    actionTestTag: String,
    modifier: Modifier = Modifier,
    actionColor: Color = RegolithTheme.colors.accent,
    muted: Boolean = true,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Eyebrow(text, Modifier.weight(1f), muted = muted)
        Text(
            action,
            style = TextStyles.buttonSmall.copy(fontSize = 12.designSp()),
            color = actionColor,
            maxLines = 1,
            modifier = Modifier.clickable(interactionSource = null, indication = null, onClick = onAction).testTag(actionTestTag),
        )
    }
}
