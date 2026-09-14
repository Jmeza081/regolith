package com.regolith.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

// On the desktop the same .ttf files sit at the root of the classpath: the
// :ui build adds ui/src/androidMain/res/font as a resource folder, so the repo
// keeps one copy of each file.

actual val Michroma: FontFamily = FontFamily(Font("michroma.ttf", FontWeight.Normal))

actual val SpaceGrotesk: FontFamily = FontFamily(
    Font("space_grotesk.ttf", FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font("space_grotesk.ttf", FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font("space_grotesk.ttf", FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font("space_grotesk.ttf", FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)
