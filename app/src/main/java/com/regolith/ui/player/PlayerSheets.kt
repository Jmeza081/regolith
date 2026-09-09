package com.regolith.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.regolith.R
import com.regolith.domain.playback.AbLoop
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.MichromaLabel
import com.regolith.ui.components.SwitchControl
import com.regolith.ui.theme.CardShape
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.SheetShape
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.util.formatClock
import com.regolith.ui.util.formatDurationShort
import com.regolith.ui.util.formatSpeed

/**
 * Sheet container for the player (design section 10). Landscape gets a
 * 344dp side panel at #0A0A0A over a 70% scrim so the picture stays
 * visible while a setting changes; portrait a bottom sheet.
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
            Modifier.fillMaxSize().background(Color(0xB3000000))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        ) {
            Column(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(344.dp).background(colors.ground)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .verticalScroll(rememberScrollState()).padding(Spacing.s18).testTag(testTag),
                verticalArrangement = Arrangement.spacedBy(Spacing.s18),
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
            Column(Modifier.padding(Spacing.s18).navigationBarsPadding().testTag(testTag), verticalArrangement = Arrangement.spacedBy(Spacing.s18)) { content() }
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
    onClose: () -> Unit,
) {
    val colors = RegolithTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        DisplayText("Playback", style = TextStyles.dialogTitle.copy(fontSize = 14.sp, lineHeight = 19.6.sp), modifier = Modifier.weight(1f))
        Box(Modifier.size(36.dp).clickable(interactionSource = null, indication = null, onClick = onClose).testTag("player_sheet_close"), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.rg_ic_close), contentDescription = "Close", tint = colors.body, modifier = Modifier.size(20.dp))
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
        MichromaLabel("Speed")
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s4)) {
            PLAYBACK_SPEEDS.forEach { s ->
                val selected = s == speed
                Box(
                    Modifier.weight(1f).height(36.dp).clip(PillShape)
                        .background(if (selected) colors.accent else colors.frostBg)
                        .then(if (selected) Modifier else Modifier.border(1.dp, colors.frostBorder, PillShape))
                        .clickable(interactionSource = null, indication = null) { onSpeed(s) }
                        .testTag("player_speed_${formatSpeed(s).dropLast(1)}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(formatSpeed(s).dropLast(1), style = if (selected) TextStyles.chipSelected else TextStyles.buttonSmall, color = if (selected) Color.White else colors.inkSoft)
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
        MichromaLabel("Decoder")
        DecoderRow("Hardware", "Direct play, lowest battery cost", selected = hardwareDecoding, onClick = { onHardwareDecoding(true) }, testTag = "player_decoder_hw")
        DecoderRow("Software", "Slower, but plays what the chip cannot", selected = !hardwareDecoding, onClick = { onHardwareDecoding(false) }, testTag = "player_decoder_sw")
    }

    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
            Text("Scrub thumbnails", style = TextStyles.settingLabel.copy(lineHeight = 20.sp), color = colors.inkSoft)
            Text("Shows a preview frame while you drag the timeline. Pulls extra data from the share.", style = TextStyles.settingMeta, color = colors.metadata)
        }
        Spacer(Modifier.width(Spacing.s12))
        SwitchControl(checked = scrubThumbnails, onCheckedChange = onScrubThumbnails, testTag = "player_scrub_thumbnails_switch")
    }
}

/** Selected decoder is a 1.5dp white border plus a red check: two channels, never colour alone. */
@Composable
private fun DecoderRow(title: String, meta: String, selected: Boolean, onClick: () -> Unit, testTag: String) {
    val colors = RegolithTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(CardShape)
            .background(colors.surface)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) colors.ink else colors.hairline, CardShape)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(Spacing.s12)
            .testTag(testTag),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
            Text(title, style = TextStyles.buttonPrimary.copy(lineHeight = 18.sp), color = if (selected) colors.ink else colors.inkSoft)
            if (selected) Text(meta, style = TextStyles.settingMeta, color = colors.metadata)
        }
        if (selected) {
            Icon(painterResource(R.drawable.rg_ic_check), contentDescription = "Selected", tint = colors.accent, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * "A–B LOOP" (design section 10): the span drawn as a red band on a
 * #161616 strip with the playhead as a 2dp white line, Point A and B
 * rows with joined −0.5s / +0.5s buttons, and Clear loop in red text.
 */
@Composable
fun AbLoopSheetContent(
    loop: AbLoop,
    positionMs: Long,
    durationMs: Long,
    onNudgeA: (Long) -> Unit,
    onNudgeB: (Long) -> Unit,
    onClear: () -> Unit,
) {
    val colors = RegolithTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s4)) {
            DisplayText("A–B loop")
            Text("${formatDurationShort(loop.lengthMs)} · repeating until cleared", style = TextStyles.meta12, color = colors.metadata)
        }
        SwitchControl(checked = true, onCheckedChange = { onClear() }, testTag = "player_loop_switch")
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
        MichromaLabel("The span")
        Column(Modifier.fillMaxWidth().background(colors.surface, CardShape).border(1.dp, colors.hairline, CardShape).padding(start = Spacing.s12, end = Spacing.s12, top = 14.dp, bottom = Spacing.s12)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val w = maxWidth
                fun at(ms: Long) = if (durationMs > 0) w * (ms.toFloat() / durationMs).coerceIn(0f, 1f) else 0.dp
                Column {
                    Box(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(8.dp)).background(colors.disabledBg)) {
                        Box(Modifier.offset(x = at(loop.aMs)).width(at(loop.bMs) - at(loop.aMs)).fillMaxHeight().background(Color(0x4DE11B17)))
                        Box(Modifier.offset(x = at(positionMs)).width(2.dp).fillMaxHeight().background(colors.ink))
                    }
                    Box(Modifier.fillMaxWidth().height(14.dp).padding(top = Spacing.s2)) {
                        // Labels sit under their points; a short loop would stack them, so B never starts before A ends.
                        val aX = (at(loop.aMs) - 16.dp).coerceAtLeast(0.dp)
                        val bX = (at(loop.bMs) - 16.dp).coerceIn(aX + 40.dp, (w - 40.dp).coerceAtLeast(aX + 40.dp))
                        Text(formatClock(loop.aMs), style = TextStyles.meta.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, lineHeight = 11.sp), color = colors.body, modifier = Modifier.offset(x = aX))
                        Text(formatClock(loop.bMs), style = TextStyles.meta.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, lineHeight = 11.sp), color = colors.body, modifier = Modifier.offset(x = bX))
                        Text(formatClock(durationMs), style = TextStyles.meta.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, lineHeight = 11.sp), color = colors.metadata, modifier = Modifier.align(Alignment.CenterEnd))
                    }
                }
            }
        }
    }

    Column {
        NudgeRow("Point A", formatClock(loop.aMs), onMinus = { onNudgeA(-AbLoop.NUDGE_MS) }, onPlus = { onNudgeA(AbLoop.NUDGE_MS) }, tag = "a")
        NudgeRow("Point B", formatClock(loop.bMs), onMinus = { onNudgeB(-AbLoop.NUDGE_MS) }, onPlus = { onNudgeB(AbLoop.NUDGE_MS) }, tag = "b")
        Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).clickable(interactionSource = null, indication = null, onClick = onClear).testTag("player_loop_clear_button"), contentAlignment = Alignment.CenterStart) {
            Text("Clear loop", style = TextStyles.buttonTertiary, color = colors.accent)
        }
    }
}

@Composable
private fun NudgeRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, tag: String) {
    val colors = RegolithTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
            Text(label, style = TextStyles.rowLabelMedium, color = colors.ink)
            Text(value, style = TextStyles.meta, color = colors.metadata)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s2)) {
            Box(
                Modifier.size(44.dp, 36.dp).clip(RoundedCornerShape(topStart = 9.dp, bottomStart = 9.dp, topEnd = 3.dp, bottomEnd = 3.dp)).background(colors.disabledBg)
                    .clickable(interactionSource = null, indication = null, onClick = onMinus).testTag("player_loop_${tag}_minus"),
                contentAlignment = Alignment.Center,
            ) { Text("−0.5s", style = TextStyles.buttonSmall.copy(fontSize = 12.sp), color = colors.inkSoft) }
            Box(
                Modifier.size(44.dp, 36.dp).clip(RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp, topEnd = 9.dp, bottomEnd = 9.dp)).background(colors.disabledBg)
                    .clickable(interactionSource = null, indication = null, onClick = onPlus).testTag("player_loop_${tag}_plus"),
                contentAlignment = Alignment.Center,
            ) { Text("+0.5s", style = TextStyles.buttonSmall.copy(fontSize = 12.sp), color = colors.inkSoft) }
        }
    }
}
