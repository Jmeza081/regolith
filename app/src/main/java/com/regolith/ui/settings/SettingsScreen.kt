package com.regolith.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.SecondaryButton
import com.regolith.ui.components.SurfaceCard
import com.regolith.ui.components.TopBar
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.util.formatBytes

/**
 * Settings tab. Phase 3 ships the Media section (design section 08: the
 * artwork cache and its Clear); shares and playback preferences follow in
 * Phase 6.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = RegolithTheme.colors
    Column(modifier.fillMaxSize().testTag("settings_screen")) {
        TopBar(title = state.title)
        Column(Modifier.padding(horizontal = Spacing.s18)) {
            Eyebrow("Media")
            Spacer(Modifier.height(Spacing.s8))
            SurfaceCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Artwork cache", style = TextStyles.rowLabel, color = colors.ink)
                        Text(
                            if (state.artworkCount == 0) "Nothing cached yet" else "${state.artworkCount} images · ${formatBytes(state.artworkBytes)}",
                            style = TextStyles.metadata,
                            color = colors.metadata,
                            modifier = Modifier.testTag("settings_artwork_meta"),
                        )
                    }
                    SecondaryButton(
                        text = "Clear",
                        onClick = viewModel::clearArtwork,
                        enabled = !state.clearing && state.artworkCount > 0,
                        testTag = "settings_clear_artwork_button",
                    )
                }
                Text(
                    "Posters and frames are read from the share and kept here. Clearing forces a fresh read; nothing on the share changes.",
                    style = TextStyles.metadata,
                    color = colors.metadata,
                    modifier = Modifier.padding(top = Spacing.s12),
                )
            }
        }
    }
}
