package com.regolith.ui.adaptive

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass

/**
 * How the window is folded, as far as the UI cares.
 *
 * Only the player reads this. A foldable's hinge is reported by Jetpack
 * WindowManager as a `FoldingFeature`; Material 3 adaptive turns that into
 * a posture, and this enum keeps the three cases the design has a layout
 * for. `FLAT` is also every phone and tablet.
 */
enum class FoldPosture {
    /** Fully open, or not a foldable at all. */
    FLAT,
    /** Half open with a vertical hinge, held like a book. Reads as flat for layout. */
    BOOK,
    /** Half open with a horizontal hinge, standing on a table: picture above the fold, controls below. */
    TABLE_TOP,
}

/**
 * The two signals every adaptive layout in the app keys on. Think of it as a
 * `useMediaQuery()` hook exposed through context.
 *
 * - [wide]: the window is at least 600dp across (Material's "medium" width
 *   class). A Galaxy Z Fold's cover screen is not wide; its inner display
 *   is, and so is any tablet. Screens switch layout on this one boolean,
 *   never on the device model.
 * - [posture] and [hinge]: only for the player's flex mode, which splits the
 *   screen exactly at the hinge. [hinge] is in window pixels.
 * - [width]: the window's own width, for the few places that need to divide
 *   it rather than just branch on it (the list pane's share of a two-pane
 *   screen).
 */
@Immutable
data class WindowShape(
    val wide: Boolean,
    val posture: FoldPosture,
    val hinge: Rect?,
    val width: Dp,
) {
    companion object {
        /** A phone: the value a screen sees outside [RegolithNavGraph][com.regolith.ui.navigation.RegolithNavGraph] (previews, tests). */
        val Phone = WindowShape(wide = false, posture = FoldPosture.FLAT, hinge = null, width = 411.dp)
    }
}

/** Read with `LocalWindowShape.current`. Provided once, at the root, by the nav graph. */
val LocalWindowShape = staticCompositionLocalOf { WindowShape.Phone }

/**
 * The live [WindowShape] for this window. Recomposes on resize, fold and
 * unfold: the manifest handles `screenSize|screenLayout` itself, so the
 * Activity is not recreated and this is a plain state change (web analogy:
 * a resize event, not a page reload).
 */
@Composable
fun rememberWindowShape(): WindowShape {
    val info = currentWindowAdaptiveInfo()
    val wide = info.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val hinge = info.windowPosture.hingeList.firstOrNull { it.isSeparating }
    val posture = when {
        info.windowPosture.isTabletop -> FoldPosture.TABLE_TOP
        hinge != null && hinge.isVertical -> FoldPosture.BOOK
        else -> FoldPosture.FLAT
    }
    val width = with(LocalDensity.current) { currentWindowSize().width.toDp() }
    return WindowShape(wide = wide, posture = posture, hinge = hinge?.bounds, width = width)
}
