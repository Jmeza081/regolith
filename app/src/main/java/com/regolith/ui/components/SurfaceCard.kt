package com.regolith.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp
import com.regolith.ui.theme.CardShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing

/** Card treatments from design section 01, "Surfaces". */
enum class CardStyle {
    /** #0F0F0F fill with a #1F1F1F hairline. The default. */
    Filled,
    /** Dashed hairline, no fill. Reserved for "nothing here yet". */
    Empty,
    /** 8% red tint with a red hairline. Error messages, never a full fill. */
    Error,
}

/**
 * The one card. 14dp radius, 18dp padding, hairline border. Use it for any
 * grouped content on a screen; do not draw ad-hoc rounded boxes in screens.
 */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    style: CardStyle = CardStyle.Filled,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(Spacing.s18),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RegolithTheme.colors
    val base = when (style) {
        CardStyle.Filled -> modifier
            .background(colors.surface, CardShape)
            .border(1.dp, colors.hairline, CardShape)
        CardStyle.Error -> modifier
            .background(colors.accentTint, CardShape)
            .border(1.dp, colors.accent.copy(alpha = 0.4f), CardShape)
        CardStyle.Empty -> modifier.drawBehind {
            drawRoundRect(
                color = colors.hairline,
                cornerRadius = CornerRadius(14.dp.toPx()),
                style = Stroke(
                    width = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                ),
            )
        }
    }
    Column(modifier = base.padding(contentPadding), content = content)
}

/** Convenience for the common "one line of text on a card" default colour. */
val CardInk: Color
    @Composable get() = RegolithTheme.colors.ink
