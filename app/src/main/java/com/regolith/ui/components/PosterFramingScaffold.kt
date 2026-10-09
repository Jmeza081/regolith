package com.regolith.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.regolith.ui.theme.Spacing
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/**
 * The frame shared by the screens that make a poster out of a picture: the
 * poster editor (a film's frame) and Set as poster (a picture on the share or
 * the phone). The picture runs full-bleed on black and the chrome floats on
 * it as frosted glass, the nav pill's material: the title bar above it and
 * the controls below in portrait, one column down the side on a wide window
 * (the Fold's inner display).
 *
 * [picture] is handed what the glass covers, so a crop box
 * ([PosterCropper]) can be fitted into what is left while the picture still
 * draws under the glass, giving the blur something to blur. [status] is
 * drawn centred in that uncovered part: a loader, or why there is nothing to
 * show. [controls] fill the glass panel, which scrolls when they do not fit.
 *
 * Web analogy: a layout component with three slots, like a page shell
 * taking `children` for its main area and its sidebar.
 */
@Composable
fun PosterFramingScaffold(
    title: String,
    subtitle: String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    picture: @Composable (covered: PaddingValues) -> Unit,
    status: @Composable BoxScope.() -> Unit = {},
    controls: @Composable ColumnScope.() -> Unit,
) {
    // The blur's source: what the panels frost is the picture under them.
    val haze = remember { HazeState() }
    val density = LocalDensity.current
    var topBarHeight by remember { mutableStateOf(0.dp) }
    var controlsHeight by remember { mutableStateOf(0.dp) }

    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        val wide = maxWidth > maxHeight
        val covered = if (wide) PaddingValues(end = POSTER_PANEL_WIDTH) else PaddingValues(top = topBarHeight, bottom = controlsHeight)
        val panel = @Composable { panelModifier: Modifier ->
            Column(
                panelModifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(Spacing.s18),
                verticalArrangement = Arrangement.spacedBy(Spacing.s12),
                content = controls,
            )
        }

        Box(Modifier.fillMaxSize().hazeSource(haze)) { picture(covered) }
        Box(Modifier.fillMaxSize().padding(covered), contentAlignment = Alignment.Center, content = status)

        if (wide) {
            Column(Modifier.align(Alignment.CenterEnd).width(POSTER_PANEL_WIDTH).fillMaxHeight().navChromeFrost(haze, RectangleShape)) {
                TopBar(title = title, subtitle = subtitle, onBack = onClose)
                panel(Modifier.weight(1f))
            }
        } else {
            TopBar(
                title = title, subtitle = subtitle, onBack = onClose,
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().navChromeFrost(haze, RectangleShape)
                    .onSizeChanged { topBarHeight = with(density) { it.height.toDp() } },
            )
            panel(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = maxHeight * 0.6f)
                    .navChromeFrost(haze, RectangleShape)
                    .onSizeChanged { controlsHeight = with(density) { it.height.toDp() } },
            )
        }
    }
}

/** The controls column beside the picture on a wide window. */
private val POSTER_PANEL_WIDTH: Dp = 380.dp
