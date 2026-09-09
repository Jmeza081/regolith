package com.regolith.ui.addserver

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.R as LucideR
import com.regolith.ui.components.ListRow
import com.regolith.ui.components.PrimaryButton
import com.regolith.ui.components.RowTrailing
import com.regolith.ui.components.TopBar
import com.regolith.ui.theme.Spacing
import com.regolith.ui.util.formatBytes

@Composable
fun SharePickerScreen(
    viewModel: SharePickerViewModel,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().navigationBarsPadding().testTag("addserver_shares_screen")) {
        TopBar(
            title = "Choose a share",
            onBack = onBack,
            meta = if (state.loaded) "${state.serverName} · ${state.shares.size} shares" else null,
        )
        LazyColumn(Modifier.weight(1f).padding(horizontal = Spacing.s18)) {
            items(state.shares, key = { it.id }) { share ->
                ListRow(
                    title = share.name,
                    meta = share.freeBytes?.let { "${formatBytes(it)} free" },
                    icon = LucideR.drawable.lucide_ic_hard_drive,
                    trailing = if (share.enabled) RowTrailing.Checked else RowTrailing.None,
                    onClick = { viewModel.toggle(share) },
                    testTag = "addserver_share_${share.name}",
                )
            }
        }
        Spacer(Modifier.height(Spacing.s12))
        PrimaryButton(
            text = when (state.selectedCount) {
                0 -> "Choose at least one share"
                1 -> "Scan 1 share"
                else -> "Scan ${state.selectedCount} shares"
            },
            onClick = onContinue,
            enabled = state.selectedCount > 0,
            testTag = "addserver_continue_button",
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.s18),
        )
        Spacer(Modifier.height(Spacing.s18))
    }
}
