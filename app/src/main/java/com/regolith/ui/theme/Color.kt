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
val SplashGround = Color(0xFF121212) // splash and lock: dark gray, so the wedge's dim bands still separate
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
val NavIdle = Color(0xFF8A8A8A)     // unselected nav labels and segmented tabs
val NavDimmed = Color(0x38FFFFFF)   // rgba(255,255,255,.22): tabs with nothing behind them
val Lifted = Color(0xFF1C1C1C)      // the one card that is open for editing, a step above surface
val LiftedBorder = Color(0xFF3A3A3A) // its hairline, a step above raised
val BadgeBg = Color(0xFF1A1A1A)     // count badge in a segmented tab, folder icon box
val DisabledBg = Color(0xFF161616)  // disabled button fill, off switch (disabled)
val DisabledInk = Color(0xFF4A4A4A)
val NoticeBg = Color(0xFF141414)    // "Couldn't reach media" notice card
val OverArt = Color(0xB8000000)     // rgba(0,0,0,.72): chips over posters
val PillBg = Color(0x85000000)      // rgba(0,0,0,.52): the nav pill's own ground under the blur
val PillBorder = Color(0x29FFFFFF)  // rgba(255,255,255,.16)
val FrostBg = Color(0x0FFFFFFF)     // rgba(255,255,255,.06): secondary buttons, on-surface chips
val FrostBorder = Color(0x2EFFFFFF) // rgba(255,255,255,.18)
val OnMediaBg = Color(0x59000000)   // rgba(0,0,0,.35): pills over the picture
val OnMediaBorder = Color(0x38FFFFFF) // rgba(255,255,255,.22)
val OnMediaCircleBg = Color(0x6B000000) // rgba(0,0,0,.42): round icon buttons over the picture
val OnMediaCircleBorder = Color(0x57FFFFFF) // rgba(255,255,255,.34)
val TrackWhite = Color(0x38FFFFFF)  // rgba(255,255,255,.22): scrubber track
val BarWhite = Color(0x40FFFFFF)    // rgba(255,255,255,.25): progress bar track on tiles

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
    val splashGround: Color = SplashGround,
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
    val navIdle: Color = NavIdle,
    val navDimmed: Color = NavDimmed,
    val badgeBg: Color = BadgeBg,
    val lifted: Color = Lifted,
    val liftedBorder: Color = LiftedBorder,
    val disabledBg: Color = DisabledBg,
    val disabledInk: Color = DisabledInk,
    val noticeBg: Color = NoticeBg,
    val overArt: Color = OverArt,
    val pillBg: Color = PillBg,
    val pillBorder: Color = PillBorder,
    val frostBg: Color = FrostBg,
    val frostBorder: Color = FrostBorder,
    val onMediaBg: Color = OnMediaBg,
    val onMediaBorder: Color = OnMediaBorder,
    val onMediaCircleBg: Color = OnMediaCircleBg,
    val onMediaCircleBorder: Color = OnMediaCircleBorder,
    val trackWhite: Color = TrackWhite,
    val barWhite: Color = BarWhite,
)

internal val LocalRegolithColors = staticCompositionLocalOf { RegolithColors() }
