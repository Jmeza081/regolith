package com.regolith.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import com.regolith.domain.playback.AbLoop
import com.regolith.ui.components.DestructiveButton
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.PillButton
import com.regolith.ui.components.RegolithSwitch
import com.regolith.ui.components.SecondaryButton
import com.regolith.ui.theme.CardShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.SheetShape
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.util.formatClock
import com.regolith.ui.util.formatDurationShort
import com.regolith.ui.util.formatSpeed

/**
 * Sheet container for the player. Landscape gets a side sheet so the
 * picture stays visible while a setting changes; portrait a bottom sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSheetHost(
    landscape: Boolean,
    onDismiss: () -> Unit,
    testTag: String,
    content: @Composable () -> Unit,
) {
    val colors = RegolithTheme.colors
    if (landscape) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.ground.copy(alpha = 0.55f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        ) {
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(360.dp)
                    .clip(RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp))
                    .background(colors.surface)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.s18)
                    .testTag(testTag),
            ) { content() }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = SheetShape,
            containerColor = colors.surface,
            contentColor = colors.ink,
            dragHandle = null,
        ) {
            Column(Modifier.padding(Spacing.s18).navigationBarsPadding().testTag(testTag)) { content() }
        }
    }
}

val PLAYBACK_SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

/** "PLAYBACK": speed, decoder, scrub thumbnails (design section 10). */
@Composable
fun PlaybackSheetContent(
    speed: Float,
    hardwareDecoding: Boolean,
    scrubThumbnails: Boolean,
    onSpeed: (Float) -> Unit,
    onHardwareDecoding: (Boolean) -> Unit,
    onScrubThumbnails: (Boolean) -> Unit,
) {
    val colors = RegolithTheme.colors
    DisplayText("Playback")
    Spacer(Modifier.height(Spacing.s18))

    Eyebrow("Speed")
    Spacer(Modifier.height(Spacing.s8))
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
        PLAYBACK_SPEEDS.forEach { s ->
            PillButton(
                text = formatSpeed(s),
                selected = s == speed,
                onMedia = false,
                onClick = { onSpeed(s) },
                testTag = "player_speed_${formatSpeed(s).dropLast(1)}",
            )
        }
    }
    Spacer(Modifier.height(Spacing.s30))

    Eyebrow("Decoder")
    Spacer(Modifier.height(Spacing.s8))
    DecoderRow("Hardware", "Direct play, lowest battery cost", selected = hardwareDecoding, onClick = { onHardwareDecoding(true) }, testTag = "player_decoder_hw")
    Spacer(Modifier.height(Spacing.s8))
    DecoderRow("Software", "Slower, but plays what the chip cannot", selected = !hardwareDecoding, onClick = { onHardwareDecoding(false) }, testTag = "player_decoder_sw")
    Spacer(Modifier.height(Spacing.s30))

    RegolithSwitch(label = "Scrub thumbnails", checked = scrubThumbnails, onCheckedChange = onScrubThumbnails, testTag = "player_scrub_thumbnails_switch")
    Text(
        "Shows a preview frame while you drag the timeline. Pulls extra data from the share.",
        style = TextStyles.metadata,
        color = colors.metadata,
        modifier = Modifier.padding(top = Spacing.s4),
    )
}

/** Selected decoder is a white border plus a red check: two channels, never colour alone. */
@Composable
private fun DecoderRow(title: String, meta: String, selected: Boolean, onClick: () -> Unit, testTag: String) {
    val colors = RegolithTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) colors.ink else colors.hairline, CardShape)
            .clickable(onClick = onClick)
            .padding(Spacing.s12)
            .testTag(testTag),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = TextStyles.rowLabel, color = colors.ink)
            Text(meta, style = TextStyles.metadata, color = colors.metadata)
        }
        if (selected) {
            Icon(painterResource(LucideR.drawable.lucide_ic_check), contentDescription = "Selected", tint = colors.accent, modifier = Modifier.size(20.dp))
        }
    }
}

/** "A–B LOOP": the span, ±0.5 s nudges for each point, Clear loop. */
@Composable
fun AbLoopSheetContent(
    loop: AbLoop,
    durationMs: Long,
    onNudgeA: (Long) -> Unit,
    onNudgeB: (Long) -> Unit,
    onClear: () -> Unit,
) {
    val colors = RegolithTheme.colors
    DisplayText("A–B loop")
    Text("${formatDurationShort(loop.lengthMs)} · repeating until cleared", style = TextStyles.metadata, color = colors.metadata)
    Spacer(Modifier.height(Spacing.s18))

    Eyebrow("The span")
    Spacer(Modifier.height(Spacing.s8))
    Row(Modifier.fillMaxWidth()) {
        Text(formatClock(loop.aMs), style = TextStyles.chip, color = colors.ink)
        Spacer(Modifier.weight(1f))
        Text(formatClock(loop.bMs), style = TextStyles.chip, color = colors.ink)
        Spacer(Modifier.weight(1f))
        Text(formatClock(durationMs), style = TextStyles.chip, color = colors.metadata)
    }
    Spacer(Modifier.height(Spacing.s18))

    NudgeRow("Point A", formatClock(loop.aMs), onMinus = { onNudgeA(-AbLoop.NUDGE_MS) }, onPlus = { onNudgeA(AbLoop.NUDGE_MS) }, tag = "a")
    Spacer(Modifier.height(Spacing.s12))
    NudgeRow("Point B", formatClock(loop.bMs), onMinus = { onNudgeB(-AbLoop.NUDGE_MS) }, onPlus = { onNudgeB(AbLoop.NUDGE_MS) }, tag = "b")
    Spacer(Modifier.height(Spacing.s30))

    DestructiveButton(text = "Clear loop", onClick = onClear, testTag = "player_loop_clear_button", modifier = Modifier.fillMaxWidth())
}

@Composable
private fun NudgeRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, tag: String) {
    val colors = RegolithTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(label, style = TextStyles.rowLabel, color = colors.ink)
            Text(value, style = TextStyles.metadata, color = colors.metadata)
        }
        SecondaryButton(text = "−0.5s", onClick = onMinus, testTag = "player_loop_${tag}_minus")
        Spacer(Modifier.width(Spacing.s8))
        SecondaryButton(text = "+0.5s", onClick = onPlus, testTag = "player_loop_${tag}_plus")
    }
}
