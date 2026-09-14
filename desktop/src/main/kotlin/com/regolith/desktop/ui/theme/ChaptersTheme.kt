package com.regolith.desktop.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.regolith.ui.theme.Michroma
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.SpaceGrotesk

/*
 * The Mac app draws with the phone's theme from :ui: the same palette, shapes
 * and faces. Only the type sizes are its own. They are the design's px
 * WITHOUT the phone's 1.28x type scale: that scale exists for a phone held at
 * arm's length, and a desktop window at 100% already renders sp as px.
 */

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
 * Root theme for the Mac app: the shared [RegolithTheme] with
 * [ChaptersTypography]. Screens read colours from `RegolithTheme.colors`,
 * as the phone's do. Web analogy: the ThemeProvider at the top of the tree.
 */
@Composable
fun ChaptersTheme(content: @Composable () -> Unit) {
    RegolithTheme(typography = ChaptersTypography, content = content)
}
