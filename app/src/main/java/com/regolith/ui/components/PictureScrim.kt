package com.regolith.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Which edge of the picture a [PictureScrim] darkens from. */
enum class ScrimEdge { TOP, BOTTOM }

/**
 * The dark wash at the top or the foot of a picture, under the words laid
 * over it: the lightbox's bars, and a story's.
 *
 * Dark where the words are — about four fifths black, so white text stays
 * legible over a white sky — and easing out over [tail] past them, so the
 * picture shows through again without a visible edge. The first version
 * faded from half-black at the screen's edge to nothing at the words'
 * bottom, which left the words on a wash barely darker than the picture:
 * the owner found too much was lost. Text on it should also wear
 * [onPicture]'s shadow, the belt to this scrim's braces.
 *
 * The gradient is measured against the scrim's own height, so the dark part
 * covers exactly the content and the fade exactly [tail], whatever the
 * status bar or the content's height. Web analogy: a `linear-gradient` whose
 * stops are set in pixels from the far edge, not in percent.
 */
@Composable
fun PictureScrim(
    edge: ScrimEdge,
    modifier: Modifier = Modifier,
    tail: Dp = SCRIM_TAIL,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .drawBehind {
                val fade = (tail.toPx() / size.height).coerceIn(0f, 1f)
                // From the picture's edge: darkest at the edge, still dark at
                // the far side of the words, then an eased fall to nothing.
                val stops = arrayOf(
                    0f to Color.Black.copy(alpha = EDGE_ALPHA),
                    (1f - fade) to Color.Black.copy(alpha = WORDS_ALPHA),
                    (1f - fade * 0.6f) to Color.Black.copy(alpha = WORDS_ALPHA * 0.5f),
                    (1f - fade * 0.25f) to Color.Black.copy(alpha = WORDS_ALPHA * 0.15f),
                    1f to Color.Transparent,
                )
                val brush = if (edge == ScrimEdge.TOP) {
                    Brush.verticalGradient(*stops)
                } else {
                    Brush.verticalGradient(*stops.map { (at, color) -> (1f - at) to color }.reversed().toTypedArray())
                }
                drawRect(brush)
            },
    ) {
        if (edge == ScrimEdge.BOTTOM) Spacer(Modifier.height(tail))
        content()
        if (edge == ScrimEdge.TOP) Spacer(Modifier.height(tail))
    }
}

/**
 * [this] style with a soft dark shadow, for words over a picture: on the
 * [PictureScrim], it keeps the letters' edges where the picture behind is
 * brightest.
 */
fun TextStyle.onPicture(): TextStyle = copy(shadow = PictureTextShadow)

private val PictureTextShadow = Shadow(color = Color(0xB3000000), offset = Offset(0f, 1f), blurRadius = 8f)

/** How far past the words a scrim takes to fade out. */
private val SCRIM_TAIL = 72.dp

/** How dark a scrim is at the picture's edge, and behind the words. */
private const val EDGE_ALPHA = 0.86f
private const val WORDS_ALPHA = 0.74f
