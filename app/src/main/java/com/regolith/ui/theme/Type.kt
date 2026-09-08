package com.regolith.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.regolith.R

/*
 * Two faces (design section 01, "Type"):
 *  - Michroma: wordmark, screen titles, eyebrows. Uppercase only. Drawn with a
 *    0.55px stroke for weight by [com.regolith.ui.components.DisplayText].
 *  - Space Grotesk: everything a person actually reads.
 *
 * Both are bundled in res/font (SIL Open Font License). Downloadable Fonts
 * was rejected: it resolves asynchronously and would flash on the splash.
 */
val Michroma = FontFamily(Font(R.font.michroma, FontWeight.Normal))

/** Space Grotesk ships as one variable font; each weight is an axis setting. */
val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.space_grotesk, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.space_grotesk, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.space_grotesk, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

/** Size/line-height pairs measured off the design. */
object TextStyles {
    /** Screen title, e.g. "ARRIVAL". Michroma; DisplayText uppercases it. */
    val screenTitle = TextStyle(fontFamily = Michroma, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.01).em)
    /** Section eyebrow, e.g. "ON THIS NETWORK". */
    val eyebrow = TextStyle(fontFamily = Michroma, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.08.em)
    /** Hero figure on the Scanning screen: the one place a number gets display type. */
    val heroFigure = TextStyle(fontFamily = Michroma, fontSize = 40.sp, lineHeight = 46.sp)
    /** Row label, 15px semibold. */
    val rowLabel = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 19.sp)
    /** Body copy, 14px, [Body] on black. */
    val body = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp)
    /** Button and chip label. */
    val label = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp)
    /** Small chip / badge text. */
    val chip = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp)
    /** Metadata, 12px, [Metadata] grey. */
    val metadata = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp)
}

/** M3 typography so Material components inherit the body face by default. */
val RegolithTypography = Typography(
    displayLarge = TextStyles.heroFigure,
    headlineMedium = TextStyles.screenTitle,
    labelSmall = TextStyles.eyebrow,
    titleMedium = TextStyles.rowLabel,
    bodyMedium = TextStyles.body,
    bodySmall = TextStyles.metadata,
    labelLarge = TextStyles.label,
    labelMedium = TextStyles.chip,
)
