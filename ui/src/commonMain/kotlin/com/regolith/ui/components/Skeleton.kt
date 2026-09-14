package com.regolith.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
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

/** The 6dp progress bar (design: forms): #1F1F1F track, red fill, pill ends. */
@Composable
fun ProgressBar(fraction: Float?, modifier: Modifier = Modifier) {
    val colors = RegolithTheme.colors
    Box(modifier.fillMaxWidth().height(6.dp).clip(PillShape).background(colors.hairline)) {
        if (fraction != null) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxSize().clip(PillShape).background(colors.accent))
        }
    }
}
