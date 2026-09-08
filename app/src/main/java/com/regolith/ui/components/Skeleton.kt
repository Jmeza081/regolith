package com.regolith.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import com.regolith.ui.theme.CardShape
import com.regolith.ui.theme.RegolithTheme

/**
 * Loading placeholder. Rests at #141414: "the geometry of what is coming,
 * never mistakable for content that arrived". No shimmer; the design's
 * loading language is stillness.
 */
@Composable
fun Skeleton(modifier: Modifier = Modifier, shape: Shape = CardShape) {
    Box(modifier.background(RegolithTheme.colors.skeleton, shape))
}
