package com.regolith.ui.addserver

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.regolith.R
import com.regolith.ui.components.CardStyle
import com.regolith.ui.components.PrimaryButton
import com.regolith.ui.components.RowAction
import com.regolith.ui.components.SurfaceCard
import androidx.compose.ui.text.style.TextOverflow
import com.regolith.ui.components.TopBar
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.util.formatBytes

/**
 * "Choose a share" (design section 03): one card per share with a 38dp
 * icon box, the name at 600 15/19 and the free space at 12px. Selection
 * inverts the box to white and thickens the border to 1.5dp white: two
 * channels, so it never rests on colour alone. "Scan N shares" at the foot.
 *
 * The card is the whole share. The chevron at its end is the other choice:
 * go inside and pick folders ([FolderPickerScreen]), for the share whose
 * `Backups/` is not something you want walked. A share with folders chosen
 * says how many where the free space would go.
 */
@Composable
fun SharePickerScreen(
    viewModel: SharePickerViewModel,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onChooseFolders: (shareId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = RegolithTheme.colors
    Column(modifier.fillMaxSize().navigationBarsPadding().testTag("addserver_shares_screen")) {
        TopBar(
            title = "Choose a share",
            onBack = onBack,
            subtitle = if (state.loaded) "${state.serverName} · ${state.shares.size} share" + (if (state.shares.size == 1) "" else "s") else null,
            subtitleMuted = true,
        )
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = Spacing.s18), verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
            items(state.shares, key = { it.id }) { share ->
                SurfaceCard(
                    style = if (share.enabled) CardStyle.Selected else CardStyle.Filled,
                    contentPadding = PaddingValues(Spacing.s12),
                    modifier = Modifier.fillMaxWidth().clickable(interactionSource = null, indication = null) { viewModel.toggle(share) }.testTag("addserver_share_${share.name}"),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(38.dp).background(if (share.enabled) colors.ink else colors.disabledBg, RoundedCornerShape(11.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(if (share.enabled) R.drawable.rg_ic_check else R.drawable.rg_ic_browse), contentDescription = null,
                                tint = if (share.enabled) colors.ground else colors.metadata, modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(Spacing.s12))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
                            Text(share.name, style = TextStyles.rowLabel, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(
                                    share.freeBytes?.let { "${formatBytes(it)} free" },
                                    when {
                                        share.roots.isNotEmpty() -> "${share.roots.size} folder" + (if (share.roots.size == 1) "" else "s") + " chosen"
                                        share.enabled -> "whole share"
                                        else -> null
                                    },
                                ).joinToString(" · ").ifEmpty { "share" },
                                style = TextStyles.meta12, color = colors.metadata, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("addserver_share_meta_${share.name}"),
                            )
                        }
                        Spacer(Modifier.width(Spacing.s8))
                        RowAction(
                            icon = R.drawable.rg_ic_chevron_right,
                            contentDescription = "Choose folders in ${share.name}",
                            onClick = { onChooseFolders(share.id) },
                            testTag = "addserver_share_folders_${share.name}",
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(Spacing.s12))
        PrimaryButton(
            text = when (state.selectedCount) {
                0 -> "Scan 0 shares"
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
