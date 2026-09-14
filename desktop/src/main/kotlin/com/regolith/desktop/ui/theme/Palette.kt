package com.regolith.desktop.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The phone's design tokens, copied from `app/.../ui/theme/Color.kt` with
 * the same names and hex values. Sharing `ui/theme` between the apps is the
 * next branch; until then, change a value in both places or neither.
 *
 * Same rules as the phone: dark only, black ground, one red reserved for the
 * single primary action on a screen, greys for everything else.
 */
object Palette {
    val Ground = Color(0xFF0A0A0A)
    val Surface = Color(0xFF0F0F0F)
    val Skeleton = Color(0xFF141414)
    val Hairline = Color(0xFF1F1F1F)
    val Raised = Color(0xFF2E2E2E)
    val Metadata = Color(0xFF6E6E6E)
    val Body = Color(0xFFA0A0A0)
    val Ink = Color(0xFFFFFFFF)
    val InkSoft = Color(0xFFEDEDED)
    val Red = Color(0xFFE11B17)
    val RedTint = Color(0x14E11B17)
    val Lifted = Color(0xFF1C1C1C)
    val LiftedBorder = Color(0xFF3A3A3A)
    val DisabledBg = Color(0xFF161616)
    val DisabledInk = Color(0xFF4A4A4A)
    val FrostBg = Color(0x0FFFFFFF)
    val FrostBorder = Color(0x2EFFFFFF)
    val TrackWhite = Color(0x38FFFFFF)
}

/** The palette on Material 3's slots, mapped exactly as the phone's `Theme.kt` does. */
internal val ChaptersColorScheme = darkColorScheme(
    primary = Palette.Red,
    onPrimary = Palette.InkSoft,
    primaryContainer = Palette.Red,
    onPrimaryContainer = Palette.InkSoft,
    secondary = Palette.Ink,
    onSecondary = Palette.Ground,
    background = Palette.Ground,
    onBackground = Palette.Ink,
    surface = Palette.Surface,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.Surface,
    onSurfaceVariant = Palette.Body,
    surfaceContainer = Palette.Skeleton,
    surfaceContainerLow = Palette.Surface,
    surfaceContainerHigh = Palette.Raised,
    outline = Palette.Metadata,
    outlineVariant = Palette.Hairline,
    error = Palette.Red,
    onError = Palette.InkSoft,
    errorContainer = Palette.RedTint,
    onErrorContainer = Palette.Ink,
    scrim = Palette.Ground,
)
