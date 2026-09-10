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
import com.regolith.R
import com.regolith.domain.playback.AbLoop
import androidx.compose.ui.platform.LocalConfiguration
import com.regolith.domain.playback.PlayerOrientation
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.SwitchControl
import com.regolith.ui.theme.CardShape
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.SheetShape
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.designSp
import com.regolith.ui.util.formatClock
import com.regolith.ui.util.formatDurationShort
import com.regolith.ui.util.formatSpeed
import com.regolith.ui.theme.scaledDp

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
    autoplayNext: Boolean,
    autoplayImmediately: Boolean,
    orientation: PlayerOrientation,
    onSpeed: (Float) -> Unit,
    onOrientation: (PlayerOrientation) -> Unit,
    onHardwareDecoding: (Boolean) -> Unit,
    onScrubThumbnails: (Boolean) -> Unit,
    onAutoplayNext: (Boolean) -> Unit,
    onAutoplayImmediately: (Boolean) -> Unit,
    onClose: () -> Unit,
    /**
     * False where this is not a sheet but a panel already on the screen (the
     * wide player's left column): the SPEED and DECODER eyebrows label it,
     * and there is nothing to close.
     */
    header: Boolean = true,
) {
    val colors = RegolithTheme.colors
    if (header) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DisplayText("Playback", style = TextStyles.dialogTitle.copy(fontSize = 14.designSp(), lineHeight = 19.6.designSp()), modifier = Modifier.weight(1f))
            Box(Modifier.size(36.dp).clickable(interactionSource = null, indication = null, onClick = onClose).testTag("player_sheet_close"), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.rg_ic_close), contentDescription = "Close", tint = colors.body, modifier = Modifier.size(20.dp))
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
        Eyebrow("Speed", muted = true)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s4)) {
            PLAYBACK_SPEEDS.forEach { s ->
                val selected = s == speed
                Box(
                    Modifier.weight(1f).height(36.scaledDp()).clip(PillShape)
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

    // Rotation sits with Speed: both are "how it plays", both are one choice
    // from a short row, and neither belongs in app Settings — you decide them
    // about the film in front of you, not about the app.
    //
    // Android 16 stopped honouring an app's orientation request on large
    // screens: a device whose SMALLEST width is 600dp or more decides for
    // itself, and `requestedOrientation` is quietly a no-op. Verified on the
    // Fold — locking Landscape rotates the cover screen and does nothing at
    // all on the inner display. Smallest width, not the current width: a
    // phone turned sideways is a wide window and still obeys perfectly well.
    val lockable = LocalConfiguration.current.smallestScreenWidthDp < LARGE_SCREEN_DP
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
        Eyebrow("Rotation", muted = true)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s4)) {
            PlayerOrientation.entries.forEach { option ->
                val selected = option == orientation
                Box(
                    Modifier.weight(1f).height(36.scaledDp()).clip(PillShape)
                        .background(if (selected && lockable) colors.accent else colors.frostBg)
                        .then(if (selected && lockable) Modifier else Modifier.border(1.dp, colors.frostBorder, PillShape))
                        .clickable(interactionSource = null, indication = null, enabled = lockable) { onOrientation(option) }
                        .testTag("player_rotation_${option.name.lowercase()}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        option.label,
                        style = if (selected && lockable) TextStyles.chipSelected else TextStyles.buttonSmall,
                        color = when {
                            !lockable -> colors.metadata
                            selected -> Color.White
                            else -> colors.inkSoft
                        },
                    )
                }
            }
        }
        if (!lockable) {
            Text(
                "This screen is large enough that Android does the deciding. The lock works on the cover screen and on a phone.",
                style = TextStyles.settingMeta,
                color = colors.metadata,
                modifier = Modifier.testTag("player_rotation_note"),
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
        Eyebrow("Decoder", muted = true)
        DecoderRow("Hardware", "Direct play, lowest battery cost", selected = hardwareDecoding, onClick = { onHardwareDecoding(true) }, testTag = "player_decoder_hw")
        DecoderRow("Software", "Slower, but plays what the chip cannot", selected = !hardwareDecoding, onClick = { onHardwareDecoding(false) }, testTag = "player_decoder_sw")
    }

    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
            Text("Scrub thumbnails", style = TextStyles.settingLabel.copy(lineHeight = 20.designSp()), color = colors.inkSoft)
            Text("Shows a preview frame while you drag the timeline. Pulls extra data from the share.", style = TextStyles.settingMeta, color = colors.metadata)
        }
        Spacer(Modifier.width(Spacing.s12))
        SwitchControl(checked = scrubThumbnails, onCheckedChange = onScrubThumbnails, testTag = "player_scrub_thumbnails_switch")
    }

    // The same two preferences as Settings › Playback: this is where you
    // reach for them once a film has started, the way Decoder mirrors
    // Settings › Playback › Hardware decoding.
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
            Text("Keep playing", style = TextStyles.settingLabel.copy(lineHeight = 20.designSp()), color = colors.inkSoft)
            Text("When a file ends, start the next one in this folder.", style = TextStyles.settingMeta, color = colors.metadata)
        }
        Spacer(Modifier.width(Spacing.s12))
        SwitchControl(checked = autoplayNext, onCheckedChange = onAutoplayNext, testTag = "player_autoplay_next_switch")
    }

    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).padding(start = Spacing.s12), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
            Text(
                "Don't ask first",
                style = TextStyles.settingLabel.copy(lineHeight = 20.designSp()),
                color = if (autoplayNext) colors.inkSoft else colors.metadata,
            )
            Text("Skip the ten-second Up next card and go straight in.", style = TextStyles.settingMeta, color = colors.metadata)
        }
        Spacer(Modifier.width(Spacing.s12))
        SwitchControl(
            checked = autoplayImmediately, onCheckedChange = onAutoplayImmediately,
            enabled = autoplayNext, testTag = "player_autoplay_immediately_switch",
        )
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
            Text(title, style = TextStyles.buttonPrimary.copy(lineHeight = 18.designSp()), color = if (selected) colors.ink else colors.inkSoft)
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
        Eyebrow("The span", muted = true)
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
                        Text(formatClock(loop.aMs), style = TextStyles.meta.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, lineHeight = 11.designSp()), color = colors.body, modifier = Modifier.offset(x = aX))
                        Text(formatClock(loop.bMs), style = TextStyles.meta.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, lineHeight = 11.designSp()), color = colors.body, modifier = Modifier.offset(x = bX))
                        Text(formatClock(durationMs), style = TextStyles.meta.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, lineHeight = 11.designSp()), color = colors.metadata, modifier = Modifier.align(Alignment.CenterEnd))
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
            ) { Text("−0.5s", style = TextStyles.buttonSmall.copy(fontSize = 12.designSp()), color = colors.inkSoft) }
            Box(
                Modifier.size(44.dp, 36.dp).clip(RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp, topEnd = 9.dp, bottomEnd = 9.dp)).background(colors.disabledBg)
                    .clickable(interactionSource = null, indication = null, onClick = onPlus).testTag("player_loop_${tag}_plus"),
                contentAlignment = Alignment.Center,
            ) { Text("+0.5s", style = TextStyles.buttonSmall.copy(fontSize = 12.designSp()), color = colors.inkSoft) }
        }
    }
}

/**
 * Where Android stops honouring an app's orientation request: a device
 * whose smallest width is at least this decides rotation for itself
 * (Android 16's removal of orientation restrictions on large screens).
 * The same 600dp threshold `WindowShape.wide` uses, measured differently —
 * smallest width, not current width.
 */
private const val LARGE_SCREEN_DP = 600
