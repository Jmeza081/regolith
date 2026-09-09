package com.regolith.ui.titledetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.composables.icons.lucide.R as LucideR
import com.regolith.ui.components.Chip
import com.regolith.ui.components.ChipStyle
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.MediaTile
import com.regolith.ui.components.PrimaryButton
import com.regolith.ui.components.Skeleton
import com.regolith.ui.components.SurfaceCard
import com.regolith.ui.components.TopBar
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.util.formatDate
import com.regolith.ui.util.formatRemaining
import kotlinx.coroutines.flow.StateFlow

/**
 * Title Detail (design section 09): the 16:9 art, the title, its chips,
 * the one red Play, and a filled card of facts with hairline dividers and
 * right-aligned values.
 */
// The ViewModel holds Media3's MetadataRetriever (an unstable API); androidx's @OptIn is what lint checks for.
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun TitleDetailScreen(
    viewModel: TitleDetailViewModel,
    onBack: () -> Unit,
    onPlay: (fileId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    TitleDetailContent(viewModel.uiState, onBack, onPlay, modifier)
}

@Composable
private fun TitleDetailContent(
    stateFlow: StateFlow<TitleDetailUiState>,
    onBack: () -> Unit,
    onPlay: (fileId: Long) -> Unit,
    modifier: Modifier,
) {
    val state by stateFlow.collectAsStateWithLifecycle()
    val colors = RegolithTheme.colors
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("detail_screen")) {
        TopBar(title = "", onBack = onBack)
        Column(Modifier.padding(horizontal = Spacing.s18)) {
            if (!state.loaded) return@Column
            MediaTile(
                artwork = state.artwork,
                title = "",
                onClick = { onPlay(state.fileId) },
                testTag = "detail_artwork",
                placeholderLabel = state.title,
            )
            Spacer(Modifier.height(Spacing.s4))
            DisplayText(state.title, maxLines = 3)
            Spacer(Modifier.height(Spacing.s8))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                state.chips.forEach { Chip(it, ChipStyle.OnSurface) }
            }
            Spacer(Modifier.height(Spacing.s18))

            val resume = state.progressMs
            val duration = state.durationMs
            PrimaryButton(
                text = if (resume != null) "Resume" else "Play",
                onClick = { onPlay(state.fileId) },
                leadingIcon = painterResource(LucideR.drawable.lucide_ic_play),
                modifier = Modifier.fillMaxWidth(),
                testTag = "detail_play_button",
            )
            if (resume != null && duration != null && duration > 0) {
                Text(
                    formatRemaining(resume, duration),
                    style = TextStyles.metadata,
                    color = colors.metadata,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.s8),
                )
            }
            Spacer(Modifier.height(Spacing.s30))

            SurfaceCard(modifier = Modifier.fillMaxWidth().testTag("detail_facts_card"), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.s18)) {
                FactRow("Path", state.path)
                HorizontalDivider(color = colors.hairline, thickness = 1.dp)
                FactRow("Video", state.videoLine, loading = state.probing)
                HorizontalDivider(color = colors.hairline, thickness = 1.dp)
                FactRow("Audio", state.audioLine, loading = state.probing)
                HorizontalDivider(color = colors.hairline, thickness = 1.dp)
                FactRow("Modified", formatDate(state.modifiedAtMs))
            }
            state.probeError?.let {
                Text(it, style = TextStyles.metadata, color = colors.metadata, modifier = Modifier.padding(top = Spacing.s8).testTag("detail_probe_error"))
            }
            Spacer(Modifier.height(Spacing.s56))
        }
    }
}

/** Label left in metadata grey, value right-aligned in the body face. */
@Composable
private fun FactRow(label: String, value: String?, loading: Boolean = false) {
    val colors = RegolithTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.s12), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = TextStyles.metadata, color = colors.metadata)
        Spacer(Modifier.weight(1f))
        when {
            value != null && value.isNotEmpty() -> Text(value, style = TextStyles.body, color = colors.ink, textAlign = TextAlign.End)
            loading || value == null -> Skeleton(Modifier.height(14.dp).fillMaxWidth(0.4f))
            else -> Text("Unknown", style = TextStyles.body, color = colors.metadata)
        }
    }
}
