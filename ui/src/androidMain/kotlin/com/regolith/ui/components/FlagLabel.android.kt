package com.regolith.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp

internal actual fun DrawScope.drawFlagLabel(label: String, centerX: Float, baselineY: Float) {
    val paint = android.graphics.Paint().apply {
        color = Color.White.toArgb()
        textSize = 9.dp.toPx()
        textAlign = android.graphics.Paint.Align.CENTER
        isFakeBoldText = true
        isAntiAlias = true
    }
    drawContext.canvas.nativeCanvas.drawText(label, centerX, baselineY, paint)
}
