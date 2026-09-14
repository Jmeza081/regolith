package com.regolith.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.regolith.ui.R

// On Android the fonts are resources (ui/src/androidMain/res/font), found by
// the id the build generates for each file. Web analogy: an @font-face whose
// url the bundler rewrites to a hashed asset.

actual val Michroma: FontFamily = FontFamily(Font(R.font.michroma, FontWeight.Normal))

actual val SpaceGrotesk: FontFamily = FontFamily(
    Font(R.font.space_grotesk, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.space_grotesk, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.space_grotesk, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.space_grotesk, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)
