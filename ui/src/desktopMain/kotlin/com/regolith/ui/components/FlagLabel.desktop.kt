package com.regolith.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Font
import org.jetbrains.skia.Paint

// Skia has no centre alignment for a single string, so measure it and shift left by half.
internal actual fun DrawScope.drawFlagLabel(label: String, centerX: Float, baselineY: Float) {
    val font = Font(null, 9.dp.toPx()).apply { isEmboldened = true }
    val paint = Paint().apply {
        color = Color.White.toArgb()
        isAntiAlias = true
    }
    val width = font.measureTextWidth(label)
    drawContext.canvas.nativeCanvas.drawString(label, centerX - width / 2, baselineY, font, paint)
}
