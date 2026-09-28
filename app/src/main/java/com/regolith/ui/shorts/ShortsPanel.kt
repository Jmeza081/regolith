package com.regolith.ui.shorts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.regolith.R
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.domain.playback.Filmstrip as StripPositions
import com.regolith.player.ShortsFrames
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.FilterChip
import com.regolith.ui.components.Filmstrip
import com.regolith.ui.components.IconCircleButton
import com.regolith.ui.components.MediaTile
import com.regolith.ui.components.PrimaryButton
import com.regolith.ui.components.SecondaryButton
import com.regolith.ui.components.StripFrame
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.scaledDp
import com.regolith.ui.util.formatClock
import kotlin.math.absoluteValue

/**
 * Where the clip on screen has got to, read off its player once per drawn
 * frame while the panel is up.
 *
 * ExoPlayer cannot be observed for its position — it has to be asked — and
 * asking four times a second (what the feed's own progress edge does) makes
 * a playhead that visibly steps. Asking every frame is cheap: it is a field
 * read on the main thread, where ExoPlayer lives anyway.
 */
@Stable
internal class ClipClock {
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)

    /** What the clip was told to do, not whether it is moving: a clip stalled on the network is still "playing". */
    var playing by mutableStateOf(true)

    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** A [ClipClock] kept in step with [player] for as long as this is composed. */
@Composable
internal fun rememberClipClock(player: Player?): ClipClock {
    val clock = remember(player) { ClipClock() }
    LaunchedEffect(player) {
        val p = player ?: return@LaunchedEffect
        while (true) {
            withFrameMillis { }
            clock.positionMs = p.currentPosition
            clock.durationMs = p.duration.takeIf { it > 0 } ?: 0L
            clock.playing = p.playWhenReady
        }
    }
    return clock
}

/**
 * The half of the screen beside the clip, when the inner display is held
 * sideways (`design/shorts-landscape/`). Upright, and on the cover screen,
 * Shorts is the full-bleed feed it has always been.
 *
 * One scrolling column, the film player's side column in shape (F9):
 * what is playing and what you can do with it, then its frames, then what
 * plays next. The rail that sits on the picture upright has nothing to do
 * here — every control on it lives in this column instead, so nothing is
 * drawn over the clip at all.
 *
 * - **Frames** is the clip's timeline. A tap jumps there and leaves the clip
 *   playing (or paused) as it was: the strip is a way to get somewhere, not
 *   a pause button. Pausing — a tap on the clip — offers Make a poster, which
 *   opens the existing poster editor on that moment; stepping frame by frame
 *   already lives there, so it is not repeated here.
 * - **Up next** is the deck's own order after this clip. A tile jumps the
 *   feed there; ✕ takes one out until the next deal. The folder chips are
 *   the biggest few of the "Play from" sheet, with "View all" for the rest.
 *
 * Stateless: the screen owns the pager, the player and the messages.
 */
@Composable
internal fun ShortsPanel(
    item: ShortItem,
    state: ShortsUiState,
    /** Where [item] is in [ShortsUiState.items]: Up next starts after it. */
    index: Int,
    strip: List<StripFrame>,
    clock: ClipClock,
    autoAdvance: Boolean,
    canMakePoster: Boolean,
    onLocate: () -> Unit,
    onKeep: () -> Unit,
    onSound: () -> Unit,
    onSeek: (positionMs: Long) -> Unit,
    onMakePoster: () -> Unit,
    onPickFolder: (folderId: Long?) -> Unit,
    /** Every folder, in the "Play from" sheet: the chips name only a few. */
    onViewAll: () -> Unit,
    onShuffle: () -> Unit,
    onAutoAdvance: () -> Unit,
    onJumpTo: (index: Int) -> Unit,
    onSkip: (ShortItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RegolithTheme.colors
    // Everything the column shows about time changes at most once a second
    // (or once a slice), so it is derived: reading the clock's position
    // directly would recompose the whole column on every frame.
    val second by remember(clock) { derivedStateOf { clock.positionMs / 1000 } }
    val ringMs by remember(clock, strip) {
        derivedStateOf { strip.minByOrNull { (it.positionMs - clock.positionMs).absoluteValue }?.positionMs ?: 0L }
    }
    val durationMs = clock.durationMs.takeIf { it > 0 } ?: item.durationMs

    Column(
        modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End))
            .padding(start = Spacing.s18, end = Spacing.s30, top = Spacing.s18, bottom = Spacing.s30)
            .testTag("shorts_panel"),
    ) {
        // The sound pill sits with the title rather than at the end of the
        // row below: that row is as wide as the panel allows and no wider, so
        // a third button there got squeezed into a sliver whenever the panel
        // narrowed — the rail sliding out, a larger font. Here the title gives
        // way to it instead.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    item.name, style = TextStyles.settingLabel, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("shorts_panel_title"),
                )
                Text(
                    listOf(item.folderLabel, item.meta).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = TextStyles.meta, color = colors.body, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.size(Spacing.s12))
            IconCircleButton(
                icon = painterResource(R.drawable.rg_ic_sliders), contentDescription = "Sound and brightness", onClick = onSound,
                testTag = "shorts_panel_sound", size = 42.scaledDp(), width = 62.scaledDp(),
            )
        }
        Spacer(Modifier.size(Spacing.s18))
        // Wraps rather than squeezes: at a large font the second pill moves
        // down a line instead of being cut to fit.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.s8),
            verticalArrangement = Arrangement.spacedBy(Spacing.s8),
        ) {
            SecondaryButton(
                text = "Show in folder", onClick = onLocate, testTag = "shorts_panel_locate",
                leadingIcon = painterResource(R.drawable.rg_ic_folder_go), compact = true,
            )
            SecondaryButton(
                text = if (item.onDevice) "On this device" else "Keep on device", onClick = onKeep, testTag = "shorts_panel_keep",
                leadingIcon = painterResource(if (item.onDevice) R.drawable.rg_ic_check else R.drawable.rg_ic_download), compact = true,
            )
        }

        Spacer(Modifier.size(Spacing.s30))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Frames", muted = true, modifier = Modifier.weight(1f))
            Text(
                "${formatClock(second * 1000)} / ${formatClock(durationMs)}",
                style = TextStyles.meta, color = colors.body, modifier = Modifier.testTag("shorts_panel_clock"),
            )
        }
        Spacer(Modifier.size(Spacing.s12))
        // Placeholders until the strip's own list arrives, so the column never
        // jumps down by a strip's height a moment after it is drawn.
        val frames = strip.ifEmpty {
            remember(item.durationMs) { StripPositions.positions(item.durationMs, ShortsFrames.STRIP_FRAMES).map { StripFrame(it) } }
        }
        Filmstrip(
            frames = frames,
            positionMs = ringMs,
            onSeek = onSeek,
            testTag = "shorts_strip",
            aspectRatio = 9f / 16f,
            showTimes = false,
            spacing = Spacing.s4,
            playhead = { clock.fraction },
        )
        Spacer(Modifier.size(Spacing.s12))
        if (!clock.playing && canMakePoster) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s12), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(
                    text = "Make a poster", onClick = onMakePoster, testTag = "shorts_make_poster",
                    leadingIcon = painterResource(com.composables.icons.lucide.R.drawable.lucide_ic_image), compact = true,
                )
                Text("from ${formatClock(second * 1000)}", style = TextStyles.meta, color = colors.body)
            }
        } else {
            Text(
                if (canMakePoster) "Tap a frame to jump there. Pause to make a poster." else "Tap a frame to jump there.",
                style = TextStyles.meta, color = colors.body, modifier = Modifier.testTag("shorts_strip_hint"),
            )
        }

        Spacer(Modifier.size(Spacing.s30))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Up next · ${index + 1} of ${state.items.size}", muted = true, modifier = Modifier.weight(1f))
            RailAction(
                painterResource(R.drawable.rg_ic_shuffle), if (state.shuffled) "Shuffling — tap to stop" else "Shuffle these clips",
                onShuffle, "shorts_panel_shuffle", selected = state.shuffled,
            )
            RailAction(
                painterResource(R.drawable.rg_ic_skip_next),
                if (autoAdvance) "Auto-advance is on — tap to stop" else "Play the next clip automatically",
                onAutoAdvance, "shorts_panel_autoadvance", selected = autoAdvance,
            )
        }
        Spacer(Modifier.size(Spacing.s4))
        // The "Play from" sheet, laid flat — but only its first few: a share
        // can hold fifty folders of clips, and a row of fifty chips is a
        // row nobody reads. The biggest four, then "View all" for the sheet.
        val chips = remember(state.folders, state.folderId) { chipFolders(state.folders, state.folderId) }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("shorts_panel_folders"),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s8),
        ) {
            FilterChip("All", selected = state.folderId == null, onClick = { onPickFolder(null) }, testTag = "shorts_chip_all")
            chips.forEach { folder ->
                FilterChip(folder.label, selected = state.folderId == folder.id, onClick = { onPickFolder(folder.id) }, testTag = "shorts_chip_${folder.id}")
            }
            if (state.folders.size > chips.size) {
                FilterChip(
                    "View all", selected = false, onClick = onViewAll, testTag = "shorts_chip_view_all",
                    icon = R.drawable.rg_ic_folder_play,
                )
            }
        }
        Spacer(Modifier.size(Spacing.s18))
        val next = state.items.drop(index + 1).take(UP_NEXT_COUNT)
        if (next.isEmpty()) {
            Text("Nothing after this one.", style = TextStyles.meta, color = colors.body, modifier = Modifier.testTag("shorts_up_next_empty"))
        }
        // Rows are chunked rather than a lazy grid, for the reason the
        // "Play from" sheet gives: a lazy grid inside a scrolling column has
        // no height to lay out in, and this is a dozen tiles, not a thousand.
        next.chunked(UP_NEXT_COLUMNS).forEachIndexed { row, clips ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = Spacing.s12),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s8),
            ) {
                clips.forEachIndexed { column, clip ->
                    val at = index + 1 + row * UP_NEXT_COLUMNS + column
                    Box(Modifier.weight(1f)) {
                        MediaTile(
                            artwork = ArtworkRequest(ArtworkOwner.File(clip.fileId), ArtworkKind.POSTER),
                            // The folder, not the filename: a camera's name
                            // for a clip is a timestamp, and the picture above
                            // already says which clip it is.
                            title = clip.folderLabel,
                            chip = clip.durationMs.takeIf { it > 0 }?.let { formatClock(it) },
                            fallbackLabel = clip.name,
                            onClick = { onJumpTo(at) },
                            testTag = "shorts_next_${clip.fileId}",
                        )
                        SkipMark(
                            contentDescription = "Skip ${clip.name} for now",
                            onClick = { onSkip(clip) },
                            testTag = "shorts_skip_${clip.fileId}",
                            modifier = Modifier.align(Alignment.TopEnd),
                        )
                    }
                }
                // Keep a short last row left-aligned instead of stretched.
                repeat(UP_NEXT_COLUMNS - clips.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * The ✕ on an Up next tile: a 22dp mark in a 44dp target, sitting in the
 * art's corner. Drawn like the pick marker on a tile being selected — black
 * over the art with a hairline — because it is the same kind of thing: a
 * small control that belongs to the tile, not part of its picture.
 */
@Composable
private fun SkipMark(contentDescription: String, onClick: () -> Unit, testTag: String, modifier: Modifier = Modifier) {
    val colors = RegolithTheme.colors
    Box(
        modifier
            .size(44.dp)
            .clickable(interactionSource = null, indication = null, role = Role.Button, onClickLabel = contentDescription, onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.TopEnd,
    ) {
        Box(
            Modifier
                .padding(top = 6.dp, end = 6.dp)
                .size(22.dp)
                .background(colors.overArt, PillShape)
                .border(1.dp, colors.onMediaCircleBorder, PillShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.rg_ic_close_small), contentDescription = contentDescription, tint = colors.ink, modifier = Modifier.size(12.dp))
        }
    }
}

/** Three rows of four: what fits beside the strip before the column has to scroll, and a little more. */
private const val UP_NEXT_COUNT = 12
private const val UP_NEXT_COLUMNS = 4
