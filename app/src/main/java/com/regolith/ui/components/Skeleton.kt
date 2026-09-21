package com.regolith.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.TileShape

/**
 * Loading placeholder. Rests at #141414: "the geometry of what is coming,
 * never mistakable for content that arrived". No shimmer; the design's
 * loading language is stillness.
 */
@Composable
fun Skeleton(modifier: Modifier = Modifier, shape: Shape = TileShape) {
    Box(modifier.background(RegolithTheme.colors.skeleton, shape))
}

/**
 * The 6dp progress bar (design: forms): #1F1F1F track, red fill, pill ends.
 *
 * @param height the chrome tier uses a thinner one; everywhere else takes the default.
 */
@Composable
fun ProgressBar(fraction: Float?, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val colors = RegolithTheme.colors
    Box(modifier.fillMaxWidth().height(height).clip(PillShape).background(colors.hairline)) {
        if (fraction != null) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxSize().clip(PillShape).background(colors.accent))
        }
    }
}

/**
 * A bar with a sheen sweeping along it, for work whose TOTAL is unknown.
 *
 * The scan is the case it exists for: a walk learns the shape of a share by
 * walking it, so there is no total to count towards and a determinate bar
 * could only lie. Use [ProgressBar] wherever a real fraction exists.
 *
 * @param height 6dp is the design's hero bar on the Scanning screen; the
 *   chrome tier uses a thinner one, since it sits under a line of text
 *   rather than under a 40pt number.
 */
@Composable
fun SweepBar(modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val colors = RegolithTheme.colors
    val transition = rememberInfiniteTransition(label = "sweep")
    val x by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "x")
    BoxWithConstraints(modifier.fillMaxWidth().height(height).clip(PillShape).background(colors.accent)) {
        val sheen = maxWidth * 0.22f
        Box(
            Modifier.width(sheen).fillMaxHeight().offset(x = (maxWidth + sheen) * x - sheen)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0x8CFFFFFF), Color.Transparent))),
        )
    }
}
