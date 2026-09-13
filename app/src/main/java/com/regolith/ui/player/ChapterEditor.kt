package com.regolith.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import com.regolith.domain.playback.ChapterDraft
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.PrimaryButton
import com.regolith.ui.components.RegolithTextField
import com.regolith.ui.components.Scrubber
import com.regolith.ui.components.SecondaryButton
import com.regolith.ui.theme.CardShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.designSp
import com.regolith.ui.util.formatClock
import kotlin.math.abs

/**
 * The chapter editor (P9, reworked in round two): where you write the
 * chapters for this film.
 *
 * It sits where the A–B loop panel sits — under the picture in portrait
 * and in the unfolded column — and in landscape or half-open it is a sheet
 * with a timeline of its own ([showScrubber]), so marking works there too.
 * The film is paused while it is open.
 *
 * Mark at the playhead, then open a row to work on it. **One point at a
 * time:** while a row is open every other mark is locked — its handle is
 * inert, its row dims and ignores taps, and Mark waits — until you close
 * it. The open row lifts to a lighter card and offers the name, a typed
 * start time, ±0.5 s and ±5 s nudges, and Delete. Marks are moved on the
 * [MarksTimeline] below the Mark button, with handles big enough to hold,
 * never on the picture's scrubber, which only scrubs. The first mark is
 * the start of the film and can only be named. Done saves the set; Cancel
 * throws the draft away (after a confirm, when it changed).
 */
@Composable
fun ChapterEditorContent(
    draft: ChapterDraft,
    positionMs: Long,
    durationMs: Long,
    showScrubber: Boolean,
    /** Drives the scrubber and the two clocks; see [SmoothProgress]. */
    smooth: SmoothProgress,
    onMark: () -> Unit,
    onSelect: (Int?) -> Unit,
    onMove: (Int, Long) -> Unit,
    onNudge: (Int, Long) -> Unit,
    onRename: (Int, String) -> Unit,
    onRemove: (Int) -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    onScrubStart: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    /** "Remove all chapters": start again from a single unnamed mark. */
    onClearAll: () -> Unit = {},
    /** Save is writing to the share: ring on the button, Cancel waits. */
    saving: Boolean = false,
) {
    val colors = RegolithTheme.colors
    val open = draft.selected
    Column(Modifier.fillMaxWidth().testTag("player_chapter_editor"), verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s4)) {
                DisplayText("Chapters")
                Text(
                    if (open != null) "Editing · one open" else "Editing · " + (if (draft.marks.size == 1) "1 mark" else "${draft.marks.size} marks"),
                    style = TextStyles.meta12, color = colors.metadata, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            SecondaryButton("Cancel", onCancel, "player_chapter_cancel_button", compact = true, enabled = !saving)
            PrimaryButton("Save", onDone, "player_chapter_done_button", compact = true, loading = saving)
        }
        if (showScrubber) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                PositionClock(smooth::clockMs, TextStyles.buttonSmall, colors.ink, reserveForMs = durationMs)
                Scrubber(
                    progress = smooth::fraction, durationMs = durationMs, buffered = smooth::buffered,
                    onScrubStart = onScrubStart, onScrub = onScrub, onScrubEnd = onScrubEnd,
                    chapters = draft.chapters, onTrackWidth = smooth::onTrackWidth,
                    modifier = Modifier.weight(1f), testTag = "player_chapter_editor_scrubber",
                )
                Text(formatClock(durationMs), style = TextStyles.buttonSmall, color = colors.body)
            }
        }
        PrimaryButton(
            "Mark at ${formatClock(smooth.clockMs())}", onMark, "player_chapter_mark_button",
            modifier = Modifier.fillMaxWidth(), enabled = open == null, leadingIcon = painterResource(LucideR.drawable.lucide_ic_plus),
        )
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s4)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("Marks", muted = true)
                Text(
                    when {
                        open == null -> "Tap a handle to open it"
                        open == 0 -> "Start mark: rename only"
                        else -> "Drag handle ${open + 1} · others locked"
                    },
                    style = TextStyles.meta, color = colors.body, maxLines = 1, modifier = Modifier.testTag("player_chapter_marks_hint"),
                )
            }
            MarksTimeline(
                draft = draft, positionMs = positionMs, durationMs = durationMs,
                onSelect = onSelect, onMove = onMove,
                onScrubStart = onScrubStart, onScrub = onScrub, onScrubEnd = onScrubEnd,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
            draft.marks.forEachIndexed { index, mark ->
                MarkRow(
                    draft = draft, index = index, isOpen = index == open, locked = open != null && index != open,
                    onSelect = onSelect, onMove = onMove, onNudge = onNudge, onRename = onRename, onRemove = onRemove,
                )
            }
        }
        // The way to start from nothing, centred under the list. No confirm:
        // it is a draft, and Cancel still throws the whole thing away.
        if (draft.marks.size > 1 || draft.marks[0].title != null) {
            Box(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp)
                    .alpha(if (open != null) LOCKED_ALPHA else 1f)
                    .clickable(interactionSource = null, indication = null, enabled = open == null, onClick = onClearAll)
                    .testTag("player_chapter_clear_all_button"),
                contentAlignment = Alignment.Center,
            ) { Text("Remove all chapters", style = TextStyles.buttonTertiary, color = colors.accent) }
        }
    }
}

/**
 * One chapter in the list. Closed: a quiet row on the sheet's ground.
 * Open: lifted to [RegolithTheme.colors.lifted] with its own hairline,
 * and unfolded into the name, the Start field with its nudges, and the
 * two ways out. Locked (another row is open): dimmed and inert.
 */
@Composable
private fun MarkRow(
    draft: ChapterDraft,
    index: Int,
    isOpen: Boolean,
    locked: Boolean,
    onSelect: (Int?) -> Unit,
    onMove: (Int, Long) -> Unit,
    onNudge: (Int, Long) -> Unit,
    onRename: (Int, String) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val colors = RegolithTheme.colors
    val mark = draft.marks[index]
    Column(
        Modifier.fillMaxWidth()
            .alpha(if (locked) LOCKED_ALPHA else 1f)
            .background(if (isOpen) colors.lifted else colors.surface, CardShape)
            .border(1.dp, if (isOpen) colors.liftedBorder else colors.hairline, CardShape)
            .padding(horizontal = Spacing.s12)
            .testTag("player_chapter_row_$index"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s12),
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                .clickable(interactionSource = null, indication = null, enabled = !locked) { onSelect(if (isOpen) null else index) }
                .testTag("player_chapter_row_${index}_head"),
        ) {
            Text("${index + 1}", style = TextStyles.meta.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = if (isOpen) colors.accent else colors.metadata, modifier = Modifier.width(18.dp))
            Text(formatClock(mark.startMs), style = TextStyles.meta, color = colors.body)
            Text(
                mark.label(index), style = TextStyles.rowLabelMedium,
                color = if (mark.title.isNullOrBlank()) colors.metadata else colors.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
        }
        if (isOpen) {
            Column(Modifier.padding(bottom = Spacing.s8), verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                RegolithTextField(
                    value = mark.title ?: "", onValueChange = { onRename(index, it) },
                    label = "Name", placeholder = "Part ${index + 1}", testTag = "player_chapter_name_field",
                )
                if (index > 0) {
                    StartField(draft = draft, index = index, onMove = onMove)
                    NudgeStrip(index = index, onNudge = onNudge)
                } else {
                    Text("The film starts here; this one stays at 0:00.", style = TextStyles.meta, color = colors.metadata)
                }
                // Two ways out, right-aligned and one word each: Done closes
                // the row, Delete drops the mark.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (index > 0) {
                        Box(
                            Modifier.defaultMinSize(minHeight = 44.dp, minWidth = 64.dp).clickable(interactionSource = null, indication = null) { onRemove(index) }.testTag("player_chapter_delete_button"),
                            contentAlignment = Alignment.Center,
                        ) { Text("Delete", style = TextStyles.buttonTertiary, color = colors.accent) }
                    }
                    Box(
                        Modifier.defaultMinSize(minHeight = 44.dp, minWidth = 64.dp).clickable(interactionSource = null, indication = null) { onSelect(null) }.testTag("player_chapter_close_button"),
                        contentAlignment = Alignment.Center,
                    ) { Text("Done", style = TextStyles.buttonTertiary, color = colors.ink) }
                }
            }
        }
    }
}

/**
 * The typed start time. Shows the mark's clock, follows it when a nudge
 * or a drag moves it, and commits what you typed on Done or when the
 * field loses focus — never per keystroke, which would jump the mark
 * around while you are still writing. A time it cannot read, or one
 * inside a second of a neighbour, turns the field red and names what
 * would be allowed; the mark does not move until it is valid.
 */
@Composable
private fun StartField(draft: ChapterDraft, index: Int, onMove: (Int, Long) -> Unit) {
    val colors = RegolithTheme.colors
    val mark = draft.marks[index]
    var text by remember(mark.startMs) { mutableStateOf(formatClock(mark.startMs)) }
    var error by remember { mutableStateOf<String?>(null) }
    val commit = {
        val ms = ChapterDraft.parseClock(text)
        val range = draft.bounds(index)
        error = when {
            ms == null -> "Use 12:30, 0:12:30 or 1:02:15.5"
            range == null -> "This one cannot move"
            ms !in range -> "Between ${formatClock(range.first)} and ${formatClock(range.last)} here"
            else -> null
        }
        if (error == null && ms != null) onMove(index, ms)
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s4)) {
        RegolithTextField(
            value = text,
            onValueChange = { text = it; error = null },
            label = "Start",
            placeholder = "0:12:30",
            isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier.onFocusChanged { if (!it.isFocused && text != formatClock(mark.startMs)) commit() },
            testTag = "player_chapter_start_field",
        )
        error?.let { Text(it, style = TextStyles.meta, color = colors.accent, modifier = Modifier.testTag("player_chapter_start_error")) }
    }
}

/** −5 s · −0.5 s · +0.5 s · +5 s, joined like the A–B sheet's pair. */
@Composable
private fun NudgeStrip(index: Int, onNudge: (Int, Long) -> Unit) {
    val colors = RegolithTheme.colors
    val steps = listOf(
        Triple(-ChapterDraft.NUDGE_COARSE_MS, "−5s", "minus5"),
        Triple(-ChapterDraft.NUDGE_FINE_MS, "−0.5s", "minus"),
        Triple(ChapterDraft.NUDGE_FINE_MS, "+0.5s", "plus"),
        Triple(ChapterDraft.NUDGE_COARSE_MS, "+5s", "plus5"),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s2)) {
        steps.forEachIndexed { i, (delta, label, tag) ->
            val shape = when (i) {
                0 -> RoundedCornerShape(topStart = 9.dp, bottomStart = 9.dp, topEnd = 3.dp, bottomEnd = 3.dp)
                steps.lastIndex -> RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp, topEnd = 9.dp, bottomEnd = 9.dp)
                else -> RoundedCornerShape(3.dp)
            }
            Box(
                Modifier.weight(1f).height(36.dp).clip(shape).background(colors.disabledBg)
                    .clickable(interactionSource = null, indication = null) { onNudge(index, delta) }
                    .testTag("player_chapter_nudge_$tag"),
                contentAlignment = Alignment.Center,
            ) { Text(label, style = TextStyles.buttonSmall.copy(fontSize = 12.designSp()), color = colors.inkSoft) }
        }
    }
}

/**
 * The marks' own timeline: a 64dp strip with a 30×40dp numbered handle
 * per mark, the chapter gaps drawn along its foot, and a thin white line
 * where the film is. Only the OPEN mark's handle can be grabbed; the rest
 * are drawn dark and take no touch. With nothing open, tapping a handle
 * opens it, and a tap or drag anywhere else scrubs the film — the same
 * callbacks the picture's scrubber uses, so the preview shows there too.
 *
 * Big handles on a strip of their own, rather than flags on the 3dp
 * scrubber: a miss by a few pixels used to scrub instead of moving the
 * mark, which is what the owner meant by "finicky".
 */
@Composable
private fun MarksTimeline(
    draft: ChapterDraft,
    positionMs: Long,
    durationMs: Long,
    onSelect: (Int?) -> Unit,
    onMove: (Int, Long) -> Unit,
    onScrubStart: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
) {
    val colors = RegolithTheme.colors
    // Read inside the gesture without restarting it: the draft changes on
    // every drag step, and a restarted pointerInput would drop the finger.
    val draftNow by rememberUpdatedState(draft)
    var draggingHandle by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableStateOf(0f) }

    Canvas(
        Modifier.fillMaxWidth().height(MARKS_HEIGHT)
            .clip(RoundedCornerShape(10.dp)).background(colors.surface).border(1.dp, colors.hairline, RoundedCornerShape(10.dp))
            .testTag("player_chapter_marks")
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    val d = draftNow
                    val hit = handleAt(d, durationMs, offset.x, size.width, HANDLE_W.toPx() / 2 + HANDLE_REACH.toPx())
                    when {
                        d.selected == null && hit > 0 -> onSelect(hit)
                        hit > 0 -> Unit // locked, or already the open one: nothing to do
                        durationMs > 0 -> { onScrubStart(); onScrubEnd((offset.x / size.width).coerceIn(0f, 1f)) }
                    }
                }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val d = draftNow
                        val hit = handleAt(d, durationMs, offset.x, size.width, HANDLE_W.toPx() / 2 + HANDLE_REACH.toPx())
                        if (d.selected != null && hit == d.selected) {
                            draggingHandle = true
                        } else if (hit <= 0 && durationMs > 0) {
                            scrubbing = true
                            scrubFraction = (offset.x / size.width).coerceIn(0f, 1f)
                            onScrubStart()
                            onScrub(scrubFraction)
                        }
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val f = (change.position.x / size.width).coerceIn(0f, 1f)
                        if (draggingHandle) draftNow.selected?.let { onMove(it, (f * durationMs).toLong()) }
                        else if (scrubbing) { scrubFraction = f; onScrub(f) }
                    },
                    onDragEnd = {
                        if (scrubbing) onScrubEnd(scrubFraction)
                        draggingHandle = false; scrubbing = false
                    },
                    onDragCancel = { draggingHandle = false; scrubbing = false },
                )
            },
    ) {
        val w = size.width
        fun xMs(ms: Long) = if (durationMs > 0) (ms.toFloat() / durationMs).coerceIn(0f, 1f) * w else 0f
        // The chapter gaps along the foot, the open mark's part in red.
        val edges = buildList {
            add(0f)
            if (durationMs > 0) draft.marks.map { it.startMs }.filter { it > 0 && it < durationMs }.forEach { add(xMs(it)) }
            add(w)
        }
        val footH = 4.dp.toPx()
        val footY = size.height - 8.dp.toPx() - footH
        val gap = 3.dp.toPx()
        val openX = draft.selected?.takeIf { it > 0 }?.let { xMs(draft.marks[it].startMs) }
        for (i in 0 until edges.size - 1) {
            val s = if (i == 0) edges[i] else edges[i] + gap / 2
            val e = if (i == edges.size - 2) edges[i + 1] else edges[i + 1] - gap / 2
            if (e <= s) continue
            val on = openX != null && abs(edges[i] - openX) < 0.5f
            drawRoundRect(if (on) colors.accent else colors.trackWhite, Offset(s, footY), Size(e - s, footH), CornerRadius(footH / 2))
        }
        // The playhead.
        drawRect(colors.ink.copy(alpha = 0.7f), Offset(xMs(positionMs) - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
        // The handles, the open one last so it rides over its neighbours.
        val hw = HANDLE_W.toPx(); val hh = HANDLE_H.toPx(); val top = 8.dp.toPx()
        val paint = android.graphics.Paint().apply { textSize = 12.dp.toPx(); textAlign = android.graphics.Paint.Align.CENTER; isFakeBoldText = true; isAntiAlias = true }
        fun handle(i: Int, open: Boolean) {
            val x = xMs(draft.marks[i].startMs)
            val fill = if (open) colors.accent else if (draft.selected != null) LOCKED_FILL else IDLE_FILL
            val stroke = if (open) Color.White else LOCKED_BORDER
            if (open) drawRoundRect(colors.accent.copy(alpha = 0.28f), Offset(x - hw / 2 - 4.dp.toPx(), top - 4.dp.toPx()), Size(hw + 8.dp.toPx(), hh + 8.dp.toPx()), CornerRadius(11.dp.toPx()))
            drawRoundRect(fill, Offset(x - hw / 2, top), Size(hw, hh), CornerRadius(8.dp.toPx()))
            drawRoundRect(stroke, Offset(x - hw / 2, top), Size(hw, hh), CornerRadius(8.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            paint.color = (if (open) Color.White else if (draft.selected != null) LOCKED_INK else IDLE_INK).toArgb()
            drawContext.canvas.nativeCanvas.drawText("${i + 1}", x, top + hh / 2 + 4.dp.toPx(), paint)
        }
        for (i in 1 until draft.marks.size) if (i != draft.selected) handle(i, open = false)
        draft.selected?.takeIf { it > 0 }?.let { handle(it, open = true) }
    }
}

/** The handle whose box (plus [reachPx] either side) holds [xPx]; the open one wins a tie; 0 (the start mark) never; -1 for none. */
private fun handleAt(draft: ChapterDraft, durationMs: Long, xPx: Float, widthPx: Int, reachPx: Float): Int {
    if (durationMs <= 0) return -1
    fun x(i: Int) = (draft.marks[i].startMs.toFloat() / durationMs).coerceIn(0f, 1f) * widthPx
    draft.selected?.takeIf { it > 0 && abs(xPx - x(it)) <= reachPx }?.let { return it }
    var best = -1
    var bestDist = reachPx
    for (i in 1 until draft.marks.size) {
        val d = abs(xPx - x(i))
        if (d <= bestDist) { best = i; bestDist = d }
    }
    return best
}

private val MARKS_HEIGHT = 64.dp
private val HANDLE_W = 30.dp
private val HANDLE_H = 40.dp
/** Extra slack around a handle's box before a touch counts as the strip. */
private val HANDLE_REACH = 6.dp
/** A locked row and a dimmed handle: readable, obviously not for touching. */
private const val LOCKED_ALPHA = 0.38f
private val IDLE_FILL = Color(0xFF2A1110)
private val LOCKED_FILL = Color(0xFF1F0E0D)
private val LOCKED_BORDER = Color(0xFF5A1C1A)
private val IDLE_INK = Color(0xFFD8B5B3)
private val LOCKED_INK = Color(0xFF8A6462)
