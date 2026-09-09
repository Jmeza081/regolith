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
 * Two faces (design section 01, "Type & nav"):
 *  - Michroma: wordmark, screen titles, the Settings section labels.
 *    Uppercase only. Drawn with a 0.55px stroke for weight by
 *    [com.regolith.ui.components.DisplayText].
 *  - Space Grotesk: everything a person actually reads, including the
 *    tracked uppercase eyebrows and nav labels.
 *
 * Every style below is a `font:` declaration copied from the design export,
 * with the design's CSS px read as sp/dp (the frames are 320 px wide; the
 * app stretches layouts, never type). Both faces are bundled in res/font.
 */
val Michroma = FontFamily(Font(R.font.michroma, FontWeight.Normal))

/** Space Grotesk ships as one variable font; each weight is an axis setting. */
val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.space_grotesk, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.space_grotesk, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.space_grotesk, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

private fun sg(weight: FontWeight, size: Int, lineHeight: Float, tracking: Float = 0f) = TextStyle(
    fontFamily = SpaceGrotesk, fontWeight = weight, fontSize = size.sp, lineHeight = lineHeight.sp, letterSpacing = tracking.em,
)

private fun michroma(size: Int, lineHeight: Float, tracking: Float = 0f) = TextStyle(
    fontFamily = Michroma, fontSize = size.sp, lineHeight = lineHeight.sp, letterSpacing = tracking.em,
)

object TextStyles {
    // --- Michroma
    /** Screen title in the top bar: `400 15px/1.3`. */
    val screenTitle = michroma(15, 19.5f)
    /** Centred empty-state title ("NO SOURCE SERVER"): `400 17px/1.4`. */
    val emptyTitle = michroma(17, 23.8f)
    /** Title Detail's title over the art: `400 19px/24px`, `-.01em`. */
    val detailTitle = michroma(19, 24f, -0.01f)
    /** Portrait player title and the wordmark: `400 21px/1.3`. */
    val wordmark = michroma(21, 27.3f)
    /** Dialog title: `400 15px/1.4`. */
    val dialogTitle = michroma(15, 21f)
    /** Settings section labels ("SHARES · 3"): `400 10px/1`, #6E6E6E. */
    val michromaLabel = michroma(10, 10f)
    /** Server name on a Settings row: `400 12px/1.3`. */
    val michromaRow = michroma(12, 15.6f)
    /** Hero figure on the Scanning screen: `400 40px`. */
    val heroFigure = michroma(40, 46f)

    // --- Space Grotesk, structural
    /** Section eyebrow ("CONTINUE WATCHING"): `600 11px/1`, `.14em`, uppercase. */
    val eyebrow = sg(FontWeight.SemiBold, 11, 11f, 0.14f)
    /** Nav pill label: `600 10px/1`, `.06em`, uppercase. */
    val navLabel = sg(FontWeight.SemiBold, 10, 10f, 0.06f)
    /** Field label inside a text field: `600 10px/1`, `.12em`, uppercase. */
    val fieldLabel = sg(FontWeight.SemiBold, 10, 10f, 0.12f)
    /** Pull-to-refresh status: `600 10px/1`, `.12em`, uppercase. */
    val refreshLabel = sg(FontWeight.SemiBold, 10, 10f, 0.12f)
    /** "SHOWING" tag on a Settings row: `600 9px/1.4`, `.1em`. */
    val tag = sg(FontWeight.SemiBold, 9, 12.6f, 0.1f)

    // --- Space Grotesk, reading
    /** Subtitle under a screen title: `400 12px/1`. */
    val subtitle = sg(FontWeight.Normal, 12, 12f)
    /** Row label, 15px semibold (design's type specimen). */
    val rowLabel = sg(FontWeight.SemiBold, 15, 19f)
    /** Row label in a card, medium: `500 14px/18px`. */
    val rowLabelMedium = sg(FontWeight.Medium, 14, 18f)
    /** Compact row label / card title: `500 13px/17px`. */
    val rowLabelSmall = sg(FontWeight.Medium, 13, 17f)
    /** Settings row label: `500 15px/19px`. */
    val settingLabel = sg(FontWeight.Medium, 15, 19f)
    /** Body copy: `400 14px/21px`, #A0A0A0. */
    val body = sg(FontWeight.Normal, 14, 21f)
    /** Notice text in a card: `500 13px/19px`, #EDEDED. */
    val notice = sg(FontWeight.Medium, 13, 19f)
    /** Metadata: `400 11px/1.4`, #6E6E6E. */
    val meta = sg(FontWeight.Normal, 11, 15.4f)
    /** Metadata, one line: `400 12px/1`, #6E6E6E. */
    val meta12 = sg(FontWeight.Normal, 12, 12f)
    /** Under a switch label: `400 12px/16px`. */
    val settingMeta = sg(FontWeight.Normal, 12, 16f)
    /** Poster tile name: `600 12px/14px`. */
    val tileName = sg(FontWeight.SemiBold, 12, 14f)
    /** Poster tile meta: `400 11px/1.2`. */
    val tileMeta = sg(FontWeight.Normal, 11, 13.2f)
    /** Filename drawn inside an unmatched tile: `700 11px/13px`. */
    val tileFilename = sg(FontWeight.Bold, 11, 13f)
    /** Fact row label on Title Detail: `400 13px/18px`. */
    val factLabel = sg(FontWeight.Normal, 13, 18f)
    /** Fact row value: `500 12px/16px`. */
    val factValue = sg(FontWeight.Medium, 12, 16f)
    /** Text field value: `500 16px/20px`. */
    val fieldValue = sg(FontWeight.Medium, 16, 20f)

    // --- Space Grotesk, controls
    /** Primary button: `700 15px/1`. */
    val buttonPrimary = sg(FontWeight.Bold, 15, 15f)
    /** Secondary button: `600 15px/1`. */
    val buttonSecondary = sg(FontWeight.SemiBold, 15, 15f)
    /** Tertiary and destructive: `600 14px/1`. */
    val buttonTertiary = sg(FontWeight.SemiBold, 14, 14f)
    /** Compact buttons (Settings "Scan all"), on-media pills, segmented tabs: `600 13px/1`. */
    val buttonSmall = sg(FontWeight.SemiBold, 13, 13f)
    /** Chip over art: `600 10px/1.3`, `.04em`. */
    val chipOverArt = sg(FontWeight.SemiBold, 10, 13f, 0.04f)
    /** Chip on a surface (frosted): `600 11px/1.2`, `.04em`. */
    val chipOnSurface = sg(FontWeight.SemiBold, 11, 13.2f, 0.04f)
    /** Selected chip (red): `700 13px/1`. */
    val chipSelected = sg(FontWeight.Bold, 13, 13f)
    /** Count badge in a segmented tab: `600 10px/1.4`. */
    val badge = sg(FontWeight.SemiBold, 10, 14f)
    /** "All" link beside an eyebrow: `600 11px/1`. */
    val link = sg(FontWeight.SemiBold, 11, 11f)

    // --- kept for the player's clock and chips
    val chip = chipOverArt
    val label = buttonTertiary
    val metadata = meta
}

/** M3 typography so Material components inherit the body face by default. */
val RegolithTypography = Typography(
    displayLarge = TextStyles.heroFigure,
    headlineMedium = TextStyles.screenTitle,
    labelSmall = TextStyles.eyebrow,
    titleMedium = TextStyles.rowLabel,
    bodyMedium = TextStyles.body,
    bodySmall = TextStyles.meta,
    labelLarge = TextStyles.buttonTertiary,
    labelMedium = TextStyles.chipOverArt,
)
