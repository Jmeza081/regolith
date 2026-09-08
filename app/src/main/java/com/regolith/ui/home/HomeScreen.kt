package com.regolith.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.R as LucideR
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.ListRow
import com.regolith.ui.components.PrimaryButton
import com.regolith.ui.components.RowTrailing
import com.regolith.ui.components.TertiaryButton
import com.regolith.ui.components.TopBar
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles

/**
 * Home tab. Phase 1 ships the "no source server" state from the design
 * and a plain list of connected servers; resume and newly-added rows come
 * with the library scan in Phase 4.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onAddServer: () -> Unit,
    onBrowse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = RegolithTheme.colors
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("home_screen")) {
        TopBar(title = "Home")
        Column(Modifier.padding(horizontal = Spacing.s18)) {
            if (!state.loaded) return@Column
            if (!state.hasSource) {
                Spacer(Modifier.height(Spacing.s30))
                DisplayText("No source\nserver")
                Spacer(Modifier.height(Spacing.s12))
                Text(
                    "Regolith plays what is already on your own network. Point it at a share and everything on it shows up here.",
                    style = TextStyles.body,
                    color = colors.body,
                )
                Spacer(Modifier.height(Spacing.s18))
                PrimaryButton(text = "Add source server", onClick = onAddServer, testTag = "home_add_server_button", modifier = Modifier.fillMaxWidth())
                TertiaryButton(text = "Enter an address", onClick = onAddServer, testTag = "home_enter_address_button", modifier = Modifier.fillMaxWidth())
            } else {
                Eyebrow("Source servers")
                Spacer(Modifier.height(Spacing.s8))
                state.serverNames.forEach { name ->
                    ListRow(title = name, icon = LucideR.drawable.lucide_ic_server, onClick = onBrowse, testTag = "home_server_$name")
                }
                Spacer(Modifier.height(Spacing.s18))
                TertiaryButton(text = "Add another", onClick = onAddServer, testTag = "home_add_server_button")
            }
            Spacer(Modifier.height(120.dp))
        }
    }
}
