package com.regolith.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.regolith.ui.theme.ThumbShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.regolith.R
import com.composables.icons.lucide.R as LucideR
import com.regolith.domain.playback.AbLoop
import com.regolith.domain.playback.Chapter
import com.regolith.domain.playback.ChapterMarks
import com.regolith.domain.playback.ChapterSource
import com.regolith.domain.playback.ChapterSyncNote
import com.regolith.domain.playback.ChapterSyncState
import androidx.compose.ui.platform.LocalConfiguration
import com.regolith.domain.playback.PlayerOrientation
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.RowAction
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.SwitchControl
import com.regolith.ui.theme.CardShape
import androidx.compose.ui.text.style.TextOverflow
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
 * Sheet container for the player. One shape in every orientation: a bottom
 * sheet.
 *
 * The design (section 10) put a 344dp side panel on the landscape player.
 * It was built and then rejected in use: a control you reach for while
 * holding a phone sideways belongs under your thumbs, not against the far
 * edge, and having the same settings arrive from two different directions
 * depending on how you were holding the device made them feel like two
 * different sheets. Scrolls internally when the content is taller than the
 * window, which a short landscape window makes likely.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSheetHost(
    onDismiss: () -> Unit,
    testTag: String,
    content: @Composable () -> Unit,
) {
    val colors = RegolithTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = SheetShape,
        containerColor = colors.surface,
        contentColor = colors.ink,
        dragHandle = null,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(Spacing.s18).navigationBarsPadding().testTag(testTag),
            verticalArrangement = Arrangement.spacedBy(Spacing.s18),
        ) { content() }
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
    ambientLight: Boolean,
    orientation: PlayerOrientation,
    onSpeed: (Float) -> Unit,
    onOrientation: (PlayerOrientation) -> Unit,
    onHardwareDecoding: (Boolean) -> Unit,
    onScrubThumbnails: (Boolean) -> Unit,
    onAutoplayNext: (Boolean) -> Unit,
    onAutoplayImmediately: (Boolean) -> Unit,
    onAmbientLight: (Boolean) -> Unit,
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

    // Settings › Display › Ambient light, reachable from where you would
    // actually notice it — the wash is the most visible thing on this screen
    // and the switch used to be two tabs away.
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
            Text("Ambient light", style = TextStyles.settingLabel.copy(lineHeight = 20.designSp()), color = colors.inkSoft)
            Text("Colour from the picture spills onto the screen around it. Costs a little battery.", style = TextStyles.settingMeta, color = colors.metadata)
        }
        Spacer(Modifier.width(Spacing.s12))
        SwitchControl(checked = ambientLight, onCheckedChange = onAmbientLight, testTag = "player_ambient_light_switch")
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
/**
 * The chapter list (the Chapters pill): a wall of stills, not a list of
 * times. A chapter is a place in a film, and the only thing that says which
 * place is the picture — the number beside it is how you got there, not what
 * you were looking for.
 *
 * Columns come from the width, so the phone gets two and the inner display
 * three or four. Frames arrive one at a time through the scrub pipeline and
 * fade in over the card's own ground; the cards never resize, so the sheet
 * does not jump as they land.
 */
@Composable
fun ChaptersSheetContent(
    chapters: List<Chapter>,
    frames: Map<Long, android.graphics.Bitmap?>,
    positionMs: Long,
    durationMs: Long,
    source: ChapterSource,
    onSeek: (Long) -> Unit,
    /** Where the user's chapters stand against the share (P10); null unless [source] is [ChapterSource.USER]. */
    sync: ChapterSyncState? = null,
    /** A one-time line about the last sync, shown under the subtitle. */
    syncNote: ChapterSyncNote? = null,
    /** Opens the editor; null for a film with no library row to keep chapters on. */
    onEdit: (() -> Unit)? = null,
    /** Deletes the user's chapters; null unless [source] is [ChapterSource.USER]. */
    onRevert: (() -> Unit)? = null,
) {
    val colors = RegolithTheme.colors
    val currentIndex = chapters.indexOfLast { it.startMs <= positionMs }
    Row(verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s4)) {
        DisplayText("Chapters")
        // Which kind you are looking at, because it changes what the names
        // mean: "Act one" was written by someone, "Part 3" is arithmetic,
        // and "Yours" is the one you can change.
        Text(
            when (source) {
                ChapterSource.USER -> {
                    val count = if (chapters.size == 1) "1 chapter" else "${chapters.size} chapters"
                    when (sync) {
                        ChapterSyncState.ON_SHARE -> "Yours · on the share · $count"
                        ChapterSyncState.WAITING -> "Yours · waiting to write · $count"
                        ChapterSyncState.READ_ONLY -> "Yours · on this phone (read-only share) · $count"
                        ChapterSyncState.FROM_SHARE -> "From the share · $count"
                        ChapterSyncState.PHONE_ONLY, null -> "Yours · on this phone · $count"
                    }
                }
                ChapterSource.CONTAINER -> "${chapters.size} marked in this file"
                ChapterSource.EVEN -> everyLabel(ChapterMarks.intervalFor(durationMs))
            },
            style = TextStyles.meta12, color = colors.metadata,
            modifier = Modifier.testTag("player_chapters_source"),
        )
        syncNote?.let { note ->
            Text(
                when (note) {
                    ChapterSyncNote.REPLACED_BY_SHARE -> "The share had a newer chapter file; it replaced this phone's copy."
                    ChapterSyncNote.FILE_STAYS -> "The share is read-only, so its chapter file stays and comes back at the next scan."
                    ChapterSyncNote.WRITE_FAILED -> "The last write to the share failed; it will be tried again at the next scan."
                },
                style = TextStyles.meta, color = colors.accent, modifier = Modifier.testTag("player_chapters_sync_note"),
            )
        }
    }
    if (onEdit != null) RowAction(LucideR.drawable.lucide_ic_pencil, "Edit chapters", onEdit, "player_chapters_edit_button")
    }
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("player_chapters_list")) {
        val columns = (maxWidth / CHAPTER_CARD_MIN).toInt().coerceIn(2, 4)
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
            chapters.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                    row.forEach { chapter ->
                        val index = chapters.indexOf(chapter)
                        ChapterCard(
                            chapter = chapter,
                            index = index,
                            frame = frames[chapter.startMs],
                            playing = index == currentIndex,
                            // The end of a chapter is the start of the next; the
                            // last runs to the end of the film, which the
                            // container never states.
                            endMs = chapters.getOrNull(index + 1)?.startMs ?: durationMs,
                            onClick = { onSeek(chapter.startMs) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Keeps the last row's cards the same width as every other
                    // row's rather than stretching two across four columns.
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    // The way back to the file's own markers or the even split. Nothing to
    // restore: the rows go, and the next kind down fills in (see
    // `PlaybackState.chapters`).
    if (onRevert != null) {
        Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).clickable(interactionSource = null, indication = null, onClick = onRevert).testTag("player_chapters_revert_button"), contentAlignment = Alignment.CenterStart) {
            Text("Revert to default chapters", style = TextStyles.buttonTertiary, color = colors.accent)
        }
    }
}

@Composable
private fun ChapterCard(
    chapter: Chapter,
    index: Int,
    frame: android.graphics.Bitmap?,
    playing: Boolean,
    endMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RegolithTheme.colors
    Column(
        modifier
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .testTag("player_chapter_$index"),
        verticalArrangement = Arrangement.spacedBy(Spacing.s8),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                // Ringed rather than tinted: the frame is the content, and
                // anything drawn over it reads as part of the picture.
                .then(if (playing) Modifier.border(2.dp, colors.accent, ThumbShape) else Modifier)
                .padding(if (playing) 3.dp else 0.dp)
                .clip(ThumbShape)
                .background(colors.skeleton),
        ) {
            val fade by animateFloatAsState(if (frame != null) 1f else 0f, label = "chapterFrame")
            frame?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().alpha(fade),
                )
            }
            Text(
                formatClock(chapter.startMs),
                style = TextStyles.meta,
                color = colors.inkSoft,
                modifier = Modifier.align(Alignment.BottomStart).padding(Spacing.s4)
                    .background(colors.overArt, PillShape).padding(horizontal = Spacing.s8, vertical = 3.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
            Text(
                chapter.label(index),
                style = TextStyles.rowLabelMedium,
                color = if (playing) colors.ink else colors.body,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (endMs > chapter.startMs) {
                Text(formatDurationShort(endMs - chapter.startMs), style = TextStyles.meta, color = colors.metadata, maxLines = 1)
            }
        }
    }
}

/** Narrower than this and a 16:9 still stops being worth looking at. */
private val CHAPTER_CARD_MIN = 150.dp

/** "Every 5 minutes" reads; "Every 5m 00s" does not. */
private fun everyLabel(intervalMs: Long?): String {
    if (intervalMs == null || intervalMs <= 0) return "Evenly spaced"
    if (intervalMs % 60_000L == 0L) {
        val minutes = intervalMs / 60_000L
        return if (minutes == 1L) "Every minute" else "Every $minutes minutes"
    }
    return "Every ${intervalMs / 1000L} seconds"
}

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
                    // No fixed height here. It used to be 14dp for an 11sp
                    // line, which cut the bottom off every number — and a
                    // clipped digit reads as a rendering fault, not a tight
                    // layout. The row is as tall as its text and the card has
                    // room underneath it.
                    Box(Modifier.fillMaxWidth().padding(top = Spacing.s4)) {
                        // Labels sit under their points, but never on top of
                        // each other: a short loop would stack A and B, and a
                        // loop that runs to the end would put B under the
                        // runtime at the right edge. Each one is held a label's
                        // width clear of the last.
                        val labelSpace = 44.dp
                        val rightEdge = (w - labelSpace).coerceAtLeast(0.dp)
                        val aX = (at(loop.aMs) - 16.dp).coerceIn(0.dp, (rightEdge - labelSpace * 2).coerceAtLeast(0.dp))
                        val bX = (at(loop.bMs) - 16.dp).coerceIn(aX + labelSpace, (rightEdge - labelSpace).coerceAtLeast(aX + labelSpace))
                        val markStyle = TextStyles.meta.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                        Text(formatClock(loop.aMs), style = markStyle, color = colors.body, maxLines = 1, modifier = Modifier.offset(x = aX))
                        Text(formatClock(loop.bMs), style = markStyle, color = colors.body, maxLines = 1, modifier = Modifier.offset(x = bX))
                        Text(formatClock(durationMs), style = markStyle, color = colors.metadata, maxLines = 1, modifier = Modifier.align(Alignment.CenterEnd))
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
internal const val LARGE_SCREEN_DP = 600
