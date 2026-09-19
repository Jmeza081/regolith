package com.regolith.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.scaledDp

/**
 * A frosted label over the picture: 700 15px white, white 14% fill, 28%
 * hairline, 8/18 padding.
 *
 * The app's one way of saying something ABOUT the video while the video is
 * on screen — "2× while held", "Brightness 60%", "Muted". It lives here
 * rather than inside `PlayerScreen` because Shorts says the same sentences
 * over the same kind of picture, and two pills a few dp apart in tone is
 * how one surface quietly becomes two.
 *
 * Note this is NOT [navChromeFrost]: that is a real blur of what is behind
 * it, which is right for chrome sitting over the app's own ground and wrong
 * over moving video — a 20dp blur of a picture that changes every frame is
 * expensive and reads as a smear. A flat white wash costs nothing and stays
 * legible over anything.
 *
 * @param icon drawn before the text. Use it when the label reports a STATE
 *   that persists rather than a value that just changed: a glyph gives the
 *   pill something to animate, which is what makes "this is happening right
 *   now" read at a glance instead of on second thought.
 * @param pulsing breathes [icon] between half and full opacity, for a state
 *   that lasts as long as a finger is down. Ignored without an [icon].
 */
@Composable
fun OnMediaLabel(
    text: String,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
    pulsing: Boolean = false,
) {
    Row(
        modifier = modifier
            .background(Color(0x24FFFFFF), PillShape)
            .border(1.dp, Color(0x47FFFFFF), PillShape)
            .padding(horizontal = Spacing.s18, vertical = Spacing.s8),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            // The pulse is the only motion in the pill. It runs off an
            // infinite transition rather than a LaunchedEffect loop so it
            // stops with the composition instead of outliving it.
            val alpha = if (!pulsing) {
                1f
            } else {
                val transition = rememberInfiniteTransition(label = "onMediaPulse")
                val value by transition.animateFloat(
                    initialValue = 1f,
                    targetValue = 0.45f,
                    animationSpec = infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse),
                    label = "onMediaPulseAlpha",
                )
                value
            }
            Icon(
                painter = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.scaledDp()).alpha(alpha),
            )
        }
        Text(text, style = TextStyles.buttonPrimary, color = Color.White)
    }
}

/**
 * One breath of the pulse. Slow enough to read as "running" rather than as
 * a warning: a fast blink is how an interface says something is wrong.
 */
private const val PULSE_MS = 650
