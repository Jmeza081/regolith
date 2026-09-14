package com.regolith.desktop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/*
 * The phone's two typefaces, loaded from the same .ttf files (the desktop
 * build reads app/src/main/res/font as resources). Sizes are the design's px
 * WITHOUT the phone's 1.28× type scale: that scale exists for a phone held at
 * arm's length, and a desktop window at 100% already renders sp ≈ px.
 */

/** Michroma: the display face, for titles only. */
private val Michroma = FontFamily(Font("michroma.ttf", FontWeight.Normal))

/** Space Grotesk ships as one variable font; each weight is an axis setting, as on the phone. */
private val SpaceGrotesk = FontFamily(
    Font("space_grotesk.ttf", FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font("space_grotesk.ttf", FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font("space_grotesk.ttf", FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

private fun sg(weight: FontWeight, size: Int, lineHeight: Int, tracking: Float = 0f) =
    TextStyle(fontFamily = SpaceGrotesk, fontWeight = weight, fontSize = size.sp, lineHeight = lineHeight.sp, letterSpacing = tracking.em)

private fun michroma(size: Int, lineHeight: Int) =
    TextStyle(fontFamily = Michroma, fontSize = size.sp, lineHeight = lineHeight.sp)

/** Material 3's slots, filled from the phone's `TextStyles` roles. */
internal val ChaptersTypography = Typography(
    headlineSmall = michroma(21, 28),                       // wordmark
    titleLarge = michroma(17, 24),                          // screen title
    titleMedium = sg(FontWeight.SemiBold, 16, 22),
    bodyLarge = sg(FontWeight.Normal, 15, 22),
    bodyMedium = sg(FontWeight.Normal, 14, 20),
    bodySmall = sg(FontWeight.Normal, 12, 17),
    labelLarge = sg(FontWeight.SemiBold, 14, 18),           // buttons
    labelSmall = sg(FontWeight.SemiBold, 11, 14, 0.14f),    // eyebrow: uppercase, tracked
)

/**
 * Root theme for the Mac app. Web analogy: the ThemeProvider at the top of
 * the tree. Palette and type are the phone's; see [Palette].
 */
@Composable
fun ChaptersTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ChaptersColorScheme, typography = ChaptersTypography, content = content)
}
