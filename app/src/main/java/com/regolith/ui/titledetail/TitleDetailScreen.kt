package com.regolith.ui.titledetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.regolith.R
import com.regolith.domain.transfer.TransferCause
import com.regolith.domain.transfer.TransferStatus
import com.regolith.ui.components.ArtworkImage
import com.regolith.ui.components.Chip
import com.regolith.ui.components.ChipStyle
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.IconCircleButton
import com.regolith.ui.components.MichromaLabel
import com.regolith.ui.components.PrimaryButton
import com.regolith.ui.components.SecondaryButton
import com.regolith.ui.components.Skeleton
import com.regolith.ui.components.SurfaceCard
import com.regolith.ui.components.TertiaryButton
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.util.formatBytes
import com.regolith.ui.util.formatDate
import com.regolith.ui.util.formatRemaining
import kotlinx.coroutines.flow.StateFlow

/**
 * Title Detail (design section 09): a 210dp hero of the art with the
 * design's two gradient overlays and a 44dp back circle over it; the
 * title in Michroma 19 with frosted chips; the progress line when
 * started; the one red Play (or Resume) beside a 48dp frosted circle
 * for keeping the file on this device; and the facts card with 46dp
 * rows, labels in #6E6E6E and right-aligned values in #EDEDED.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun TitleDetailScreen(
    viewModel: TitleDetailViewModel,
    onBack: () -> Unit,
    onPlay: (fileId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    TitleDetailContent(viewModel.uiState, onBack, onPlay, viewModel::keepOnDevice, viewModel::removeFromDevice, modifier)
}

@Composable
private fun TitleDetailContent(
    stateFlow: StateFlow<TitleDetailUiState>,
    onBack: () -> Unit,
    onPlay: (fileId: Long) -> Unit,
    onKeep: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier,
) {
    val state by stateFlow.collectAsStateWithLifecycle()
    val colors = RegolithTheme.colors
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("detail_screen")) {
        // Hero: the art runs under the status bar; the overlays are the design's exact stops.
        Box(Modifier.fillMaxWidth().height(210.dp + 24.dp)) {
            ArtworkImage(state.artwork, Modifier.fillMaxSize(), fallbackLabel = state.title)
            Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0x2EFFFFFF), Color.Transparent), radius = 700f)))
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0f to Color(0x99000000), 0.32f to Color.Transparent, 1f to Color(0xF5000000)),
                ),
            )
            Box(Modifier.statusBarsPadding().padding(start = Spacing.s12, top = Spacing.s12)) {
                IconCircleButton(
                    icon = painterResource(R.drawable.rg_ic_back), contentDescription = "Back", onClick = onBack,
                    onMedia = true, size = 44.dp, iconSize = 20.dp, testTag = "topbar_back_button",
                )
            }
        }
        if (!state.loaded) return@Column
        Column(Modifier.padding(horizontal = Spacing.s18), verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                DisplayText(state.title, style = TextStyles.detailTitle, maxLines = 3)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s4)) {
                    state.chips.forEach { Chip(it, ChipStyle.OnSurface) }
                }
            }

            val resume = state.progressMs
            val duration = state.durationMs
            if (resume != null && duration != null && duration > 0) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                    Box(Modifier.fillMaxWidth().height(3.dp).background(colors.hairline, PillShape)) {
                        Box(Modifier.fillMaxWidth((resume.toFloat() / duration).coerceIn(0f, 1f)).height(3.dp).background(colors.accent, PillShape))
                    }
                    Text(formatRemaining(resume, duration), style = TextStyles.meta12, color = colors.metadata)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s8), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(
                    text = if (resume != null) "Resume" else "Play",
                    onClick = { onPlay(state.fileId) },
                    leadingIcon = painterResource(R.drawable.rg_ic_play),
                    modifier = Modifier.weight(1f),
                    testTag = "detail_play_button",
                )
                KeepButton(state.transfer, onKeep, onRemove)
            }
            TransferLine(state.transfer, onKeep, onRemove)

            SurfaceCard(modifier = Modifier.fillMaxWidth().testTag("detail_facts_card"), contentPadding = PaddingValues(horizontal = Spacing.s12)) {
                FactRow("Path", state.path)
                FactRow("Video", state.videoLine, loading = state.probing)
                FactRow("Audio", state.audioLine, loading = state.probing)
                FactRow("Modified", formatDate(state.modifiedAtMs))
            }
            state.probeError?.let {
                Text(it, style = TextStyles.meta, color = colors.metadata, modifier = Modifier.testTag("detail_probe_error"))
            }
            if (state.siblings.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                    MichromaLabel("In this collection")
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                        state.siblings.forEach { s ->
                            com.regolith.ui.components.MediaTile(
                                artwork = s.artwork, kind = com.regolith.domain.artwork.ArtworkKind.THUMB, title = s.name, meta = s.meta,
                                resolution = s.resolutionLabel.ifEmpty { null }, onClick = { onPlay(s.fileId) }, testTag = "detail_sibling_${s.fileId}",
                                modifier = Modifier.weight(1f), shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(Spacing.s30))
        }
    }
}

/** The 48dp frosted circle beside Play: download to keep, or a check when the copy is here. */
@Composable
private fun KeepButton(transfer: TransferView?, onKeep: () -> Unit, onRemove: () -> Unit) {
    when (transfer?.status) {
        null, TransferStatus.FAILED -> IconCircleButton(painterResource(R.drawable.rg_ic_download), "Keep on this device", onKeep, testTag = "detail_keep_button")
        TransferStatus.DONE -> IconCircleButton(painterResource(R.drawable.rg_ic_check), "On this device", onRemove, testTag = "detail_remove_button")
        else -> IconCircleButton(painterResource(R.drawable.rg_ic_close), "Cancel", onRemove, testTag = "detail_cancel_button")
    }
}

/** One line under the buttons saying what the copy is doing; nothing when there is no copy. */
@Composable
private fun TransferLine(transfer: TransferView?, onKeep: () -> Unit, onRemove: () -> Unit) {
    val colors = RegolithTheme.colors
    if (transfer == null) return
    val line = when (transfer.status) {
        TransferStatus.DONE -> "On this device · plays with no network"
        TransferStatus.PAUSED -> "Waiting for the share · paused at ${formatBytes(transfer.bytesDone)} of ${formatBytes(transfer.totalBytes)}, resumes on its own"
        TransferStatus.QUEUED -> "Queued · ${formatBytes(transfer.totalBytes)}"
        TransferStatus.RUNNING -> "Downloading · ${(transfer.fraction * 100).toInt()}% · ${formatBytes(transfer.bytesDone)} of ${formatBytes(transfer.totalBytes)}"
        TransferStatus.FAILED -> when (transfer.cause) {
            TransferCause.NO_ROOM -> "No room · ${formatBytes(transfer.causeBytes ?: 0)} needed"
            TransferCause.SHARE_DROPPED -> "The share dropped · ${formatBytes(transfer.bytesDone)} of ${formatBytes(transfer.totalBytes)}"
            else -> "The copy failed"
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(line, style = TextStyles.meta12.copy(lineHeight = 16.sp()), color = colors.metadata, modifier = Modifier.weight(1f).testTag(if (transfer.status == TransferStatus.DONE) "detail_on_device" else "detail_transfer_line"))
            if (transfer.status == TransferStatus.FAILED) SecondaryButton(text = "Try again", onClick = onKeep, compact = true, testTag = "detail_retry_button")
            if (transfer.status == TransferStatus.DONE) TertiaryButton(text = "Remove", onClick = onRemove, testTag = "detail_remove_text_button")
        }
        if (transfer.status == TransferStatus.RUNNING || transfer.status == TransferStatus.PAUSED) {
            Box(Modifier.fillMaxWidth().height(3.dp).background(colors.hairline, PillShape).testTag("detail_transfer_progress")) {
                Box(Modifier.fillMaxWidth(transfer.fraction).height(3.dp).background(colors.ink, PillShape))
            }
        }
    }
}

private fun Int.sp() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)

/** Label left at 400 13/18 in #6E6E6E, value right-aligned at 500 12/16 in #EDEDED, 46dp minimum. */
@Composable
private fun FactRow(label: String, value: String?, loading: Boolean = false) {
    val colors = RegolithTheme.colors
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 46.dp).padding(vertical = Spacing.s8), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = TextStyles.factLabel, color = colors.metadata)
        Spacer(Modifier.width(Spacing.s12))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            when {
                value != null && value.isNotEmpty() -> Text(value, style = TextStyles.factValue, color = colors.inkSoft, textAlign = TextAlign.End)
                loading || value == null -> Skeleton(Modifier.height(12.dp).fillMaxWidth(0.4f))
                else -> Text("Unknown", style = TextStyles.factValue, color = colors.metadata)
            }
        }
    }
}
