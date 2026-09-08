package com.regolith.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * The palette from the design system (section 01). Regolith is dark only:
 * black ground, one red reserved for the single primary action on a screen,
 * greys for everything else. There is no light theme.
 */
val Ground = Color(0xFF0A0A0A)      // page background
val Surface = Color(0xFF0F0F0F)     // cards, sheets: one step up from ground
val Skeleton = Color(0xFF141414)    // loading placeholders
val Hairline = Color(0xFF1F1F1F)    // 1dp borders and dividers
val Raised = Color(0xFF2E2E2E)      // pressed / knob / secondary outline
val Metadata = Color(0xFF6E6E6E)    // 12px metadata text, disabled ink
val Body = Color(0xFFA0A0A0)        // 14px body copy
val Ink = Color(0xFFFFFFFF)         // titles, labels, primary ink
val InkSoft = Color(0xFFEDEDED)     // ink on red / on media
val Red = Color(0xFFE11B17)         // THE accent. One filled red per screen.
val RedTint = Color(0x14E11B17)     // 8% red: error card tint, never a fill

/**
 * Tokens Material 3 has no slot for. Read them with `RegolithTheme.colors`.
 *
 * Why a second colour object: M3's scheme covers primary/surface/outline, but
 * the design also names a skeleton shade, a metadata shade and an 8% error
 * tint. Overloading M3 slots for those would make `MaterialTheme.colorScheme`
 * lie about its meaning, so they live here beside it.
 */
@Immutable
data class RegolithColors(
    val ground: Color = Ground,
    val surface: Color = Surface,
    val skeleton: Color = Skeleton,
    val hairline: Color = Hairline,
    val raised: Color = Raised,
    val metadata: Color = Metadata,
    val body: Color = Body,
    val ink: Color = Ink,
    val inkSoft: Color = InkSoft,
    val accent: Color = Red,
    val accentTint: Color = RedTint,
)

internal val LocalRegolithColors = staticCompositionLocalOf { RegolithColors() }
