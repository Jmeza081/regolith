package com.regolith.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/*
 * Two faces (design section 01, "Type & nav"):
 *  - Michroma: the app's own voice, and only that -- wordmark, screen
 *    titles, dialog and empty-state titles. Never a label, never content.
 *    Uppercase only. Drawn with a 0.55px stroke for weight by
 *    [com.regolith.ui.components.DisplayText].
 *  - Space Grotesk: everything else, including every section eyebrow and
 *    every row. One eyebrow style app-wide; Settings used to have its own
 *    Michroma one, which put the display face on wayfinding and made the
 *    screen read as three competing headings.
 *
 * Every style below is a `font:` declaration copied verbatim from the design
 * export, in the design's own CSS px, then multiplied by [TYPE_SCALE] on the
 * way to sp. Keeping the raw numbers means a style can still be diffed
 * against the export. Both faces are bundled once, in
 * ui/src/androidMain/res/font; the Mac build reads the same files.
 */

/**
 * Design px -> sp multiplier, per platform: 1.28 on the phone, 1 on the desktop.
 *
 * The design frames are 320 px wide; the phone this targets is 411 dp. Reading
 * the export's px straight as sp made every label ~28% smaller *relative to the
 * screen* than the mock shows, because layouts stretch to the real width but
 * type did not. 411/320 = 1.284, so type now occupies the same fraction of the
 * screen width as it does in the frames.
 *
 * The desktop has no such mismatch: a window is not a 320 px frame stretched
 * across a phone, and a Mac at 100% already draws a design px as a point, so
 * its value is 1. Each platform defines it (`Scale.android.kt`,
 * `Scale.desktop.kt`); shared components read it through [scaledDp] and
 * [designSp] and never need to know which one they run on.
 *
 * sp (not dp) so the user's font-size accessibility setting still applies on
 * top; this factor only fixes the design-to-device mismatch.
 */
expect val TYPE_SCALE: Float

/**
 * Design px -> dp multiplier for anything drawn at a FIXED size. It follows
 * [TYPE_SCALE], so it is 1 on the desktop.
 *
 * The frames are 320 px wide and the phone is 411 dp, so a 112 px poster
 * that filled 35% of a frame fills 27% of the screen. Every fixed size in
 * the export carries that same 22% shortfall: buttons, thumbnails,
 * posters, glyphs. A button is type in a box and a thumbnail is art in a
 * box; both are ratios the frames drew, not absolutes.
 *
 * Two kinds of number are deliberately NOT scaled:
 *  - anything already expressed as a fraction of the width. The Library
 *    and Browse grids divide what is left after the gutters, so their
 *    tiles land at 29% and 45% of the screen against the frames' 28% and
 *    43%. They were never wrong, and scaling them would make them wrong.
 *  - gutters, gaps, and the 44dp hit targets. Room across is what a wider
 *    screen is for, and 44dp is an ergonomic floor, not a proportion.
 */
val SIZE_SCALE: Float get() = TYPE_SCALE

/** A design-export px size (a 48 dp button, a 52 dp thumb, a 19 dp glyph) as scaled dp. */
fun Number.scaledDp(): Dp = (toFloat() * SIZE_SCALE).dp

/**
 * A design-export px value as a scaled [TextUnit], for the few call sites that
 * override a style's `fontSize`/`lineHeight` with `style.copy(...)`. Pass the
 * number straight from the design (`13`, `19.6`) — never a pre-scaled one.
 */
fun Number.designSp(): TextUnit = (toFloat() * TYPE_SCALE).sp

/**
 * Michroma: display face.
 *
 * Declared here and defined once per platform (`expect`/`actual`, like a
 * package.json `browser` field swapping one module for another): the phone
 * loads the file as an Android font resource, the Mac from the classpath.
 */
expect val Michroma: FontFamily

/**
 * Space Grotesk ships as one variable font; each weight is an axis setting.
 * Loaded per platform, as [Michroma] is.
 */
expect val SpaceGrotesk: FontFamily

// `size`/`lineHeight` are the design's px; `tracking` is in em, so it is already
// relative to the font size and must NOT be scaled.
private fun sg(weight: FontWeight, size: Int, lineHeight: Float, tracking: Float = 0f) = TextStyle(
    fontFamily = SpaceGrotesk, fontWeight = weight, fontSize = size.designSp(), lineHeight = lineHeight.designSp(), letterSpacing = tracking.em,
)

private fun michroma(size: Int, lineHeight: Float, tracking: Float = 0f) = TextStyle(
    fontFamily = Michroma, fontSize = size.designSp(), lineHeight = lineHeight.designSp(), letterSpacing = tracking.em,
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
    /**
     * Row label in a card. The export drew 14/18 here and 15/19 on Settings
     * rows -- one point apart, same weight, side by side across cards.
     * Collapsed onto [settingLabel]: one row size, one compact size.
     */
    val rowLabelMedium get() = settingLabel
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
