package com.regolith.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/*
 * The design's palette mapped onto Material 3's colour slots ONCE, here.
 * Screens and components read `MaterialTheme.colorScheme` or
 * `RegolithTheme.colors`; they never reference a hex literal.
 *
 * primary AND error are the same red on purpose: the design uses one red for
 * "the action this screen wants" and for destructive confirms, and flags that
 * ambiguity as the direction's real cost. We keep it faithful.
 */
private val RegolithColorScheme = darkColorScheme(
    primary = Red,
    onPrimary = InkSoft,
    primaryContainer = Red,
    onPrimaryContainer = InkSoft,
    secondary = Ink,
    onSecondary = Ground,
    background = Ground,
    onBackground = Ink,
    surface = Surface,
    onSurface = Ink,
    surfaceVariant = Surface,
    onSurfaceVariant = Body,
    surfaceContainer = Skeleton,
    surfaceContainerLow = Surface,
    surfaceContainerHigh = Raised,
    outline = Metadata,
    outlineVariant = Hairline,
    error = Red,
    onError = InkSoft,
    errorContainer = RedTint,
    onErrorContainer = Ink,
    scrim = Ground,
)

/**
 * Root theme. Wrap the whole app in this once (the phone's MainActivity and
 * the Mac app's `ChaptersTheme` do).
 * Web analogy: the ThemeProvider at the top of a React tree.
 *
 * @param typography Material's type slots. The phone uses the default; the
 *   Mac passes the same faces at the design's own sizes, without the phone's
 *   [TYPE_SCALE].
 */
@Composable
fun RegolithTheme(typography: Typography = RegolithTypography, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRegolithColors provides RegolithColors()) {
        MaterialTheme(
            colorScheme = RegolithColorScheme,
            typography = typography,
            shapes = RegolithShapes,
            content = content,
        )
    }
}

/** Accessor for the extra tokens, mirroring `MaterialTheme.colorScheme`. */
object RegolithTheme {
    val colors: RegolithColors
        @Composable @ReadOnlyComposable get() = LocalRegolithColors.current
}
