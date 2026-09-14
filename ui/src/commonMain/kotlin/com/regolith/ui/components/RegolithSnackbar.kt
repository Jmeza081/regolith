package com.regolith.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.regolith.ui.theme.CardShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles

/**
 * A line at the bottom of a screen that says what just happened — "Saved
 * to the share" — and goes away on its own. Material's host, dressed as
 * a Regolith card: surface fill, raised hairline, body type. Show one with
 * `state.showSnackbar(message)`; the default duration is short.
 *
 * Put it at the bottom of the screen's root Box with
 * `Modifier.align(Alignment.BottomCenter)`; it pads itself clear of the
 * navigation bar.
 */
@Composable
fun RegolithSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    val colors = RegolithTheme.colors
    SnackbarHost(hostState = state, modifier = modifier.navigationBarsPadding().padding(Spacing.s18)) { data ->
        Text(
            data.visuals.message,
            style = TextStyles.rowLabelMedium, color = colors.ink,
            modifier = Modifier.fillMaxWidth()
                .background(colors.surface, CardShape).border(1.dp, colors.raised, CardShape)
                .padding(horizontal = Spacing.s18, vertical = Spacing.s12)
                .testTag("snackbar"),
        )
    }
}
