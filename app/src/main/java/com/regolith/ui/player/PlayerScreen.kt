package com.regolith.ui.player

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import com.composables.icons.lucide.R as LucideR
import com.regolith.domain.playback.SeekStacker
import com.regolith.player.PlaybackState
import com.regolith.ui.components.Chip
import com.regolith.ui.components.ChipStyle
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.ErrorCard
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.ListRow
import com.regolith.ui.components.PillButton
import com.regolith.ui.components.RowTrailing
import com.regolith.ui.components.Scrubber
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.util.formatBytes
import com.regolith.ui.util.formatClock
import com.regolith.ui.util.formatDurationShort
import com.regolith.ui.util.formatSpeed
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private enum class Sheet { Playback, AbLoop }
private enum class DragKind { Brightness, Volume }
private data class DragOverlay(val kind: DragKind, val fraction: Float, val xFraction: Float)

/**
 * The player (design section 10). Landscape is the engine: immersive,
 * every control on the picture. Portrait is the same engine with fewer
 * controls on the picture and the title, chips, pills and "next in this
 * folder" underneath.
 *
 * Immersive mode and orientation are Activity-level settings, applied in
 * a DisposableEffect and undone when the screen leaves.
 */
@UnstableApi
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val player by viewModel.player.collectAsStateWithLifecycle()
    val scrubThumbnails by viewModel.scrubThumbnails.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val system = remember(activity) { PlayerSystemControls(activity) }

    // Follow the phone's rotation; immersive only in landscape.
    DisposableEffect(activity, landscape) {
        val window = activity?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        if (landscape) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            system.resetBrightness()
        }
    }

    // Save progress when the app goes to the background mid-playback.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) viewModel.onPause() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var fill by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf<DragOverlay?>(null) }
    var seekLabel by remember { mutableStateOf<String?>(null) }
    var scrubPreviewMs by remember { mutableStateOf<Long?>(null) }
    val stacker = remember { SeekStacker() }

    // Controls auto-hide 3 s after the last interaction while playing.
    LaunchedEffect(controlsVisible, state.isPlaying, sheet) {
        if (controlsVisible && state.isPlaying && sheet == null) {
            delay(3_000)
            controlsVisible = false
        }
    }
    // The "+20s" pill fades when the stacking window closes.
    LaunchedEffect(seekLabel) {
        if (seekLabel != null) {
            delay(SeekStacker.WINDOW_MS + 200)
            seekLabel = null
            stacker.reset()
        }
    }

    val gestures = remember(viewModel, system) {
        object : PlayerGestureCallbacks {
            private var dragValue = 0f
            override fun onTap(zone: Zone) {
                val now = System.currentTimeMillis()
                val stacked = stacker.onSingleTap(if (zone == Zone.LEFT) -1 else 1, now)
                if (stacked != null) {
                    viewModel.seekBy(stacked)
                    seekLabel = stacker.pendingLabel(now)
                } else {
                    controlsVisible = !controlsVisible
                }
            }
            override fun onDoubleTap(zone: Zone) {
                val now = System.currentTimeMillis()
                viewModel.seekBy(stacker.onDoubleTap(if (zone == Zone.LEFT) -1 else 1, now))
                seekLabel = stacker.pendingLabel(now)
            }
            override fun onLongPressStart() = viewModel.holdFast(true)
            override fun onPressReleased() { if (state.holdingFast) viewModel.holdFast(false) }
            override fun onDragStart(zone: Zone, xFraction: Float) {
                val kind = if (zone == Zone.LEFT) DragKind.Brightness else DragKind.Volume
                dragValue = if (kind == DragKind.Brightness) system.brightness() else system.volume()
                drag = DragOverlay(kind, dragValue, xFraction)
                controlsVisible = false
            }
            override fun onDrag(dyFraction: Float) {
                val d = drag ?: return
                dragValue = (dragValue - dyFraction * 1.5f).coerceIn(0f, 1f)
                if (d.kind == DragKind.Brightness) system.setBrightness(dragValue) else system.setVolume(dragValue)
                drag = d.copy(fraction = dragValue)
            }
            override fun onDragEnd(flingDown: Boolean) {
                drag = null
                if (flingDown) onBack()
            }
            override fun onZoom(factor: Float) {
                if (factor > 1.02f) fill = true else if (factor < 0.98f) fill = false
            }
        }
    }

    val video = @Composable {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            player?.let { p ->
                ContentFrame(p, Modifier.fillMaxSize(), SURFACE_TYPE_SURFACE_VIEW, if (fill) ContentScale.Crop else ContentScale.Fit)
            }
            Box(Modifier.fillMaxSize().playerGestures(gestures).testTag("player_gesture_layer"))
            if (state.isBuffering) {
                CircularProgressIndicator(
                    color = RegolithTheme.colors.accent,
                    trackColor = Color.Transparent,
                    modifier = Modifier.align(Alignment.Center).size(48.dp).testTag("player_buffering"),
                )
            }
            VideoChrome(
                state = state,
                landscape = landscape,
                visible = controlsVisible && drag == null,
                scrubPreviewMs = scrubPreviewMs,
                onBack = onBack,
                onTogglePlay = { viewModel.togglePlayPause(); controlsVisible = true },
                onSeekBy = { viewModel.seekBy(it); controlsVisible = true },
                onScrubStart = { scrubPreviewMs = state.positionMs },
                onScrub = { f -> scrubPreviewMs = (f * state.durationMs).toLong() },
                onScrubEnd = { f ->
                    scrubPreviewMs = null
                    viewModel.seekTo((f * state.durationMs).toLong())
                    controlsVisible = true
                },
                onOpenPlayback = { sheet = Sheet.Playback },
                onLoopTap = { if (state.loop != null) sheet = Sheet.AbLoop else viewModel.tapLoopPoint() },
                onLoopClear = viewModel::clearLoop,
            )
            drag?.let { DragRail(it) }
            seekLabel?.let { SeekPill(it, left = it.startsWith("−")) }
            if (state.holdingFast) {
                Text("2× while held", style = TextStyles.chip, color = RegolithTheme.colors.ink,
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = Spacing.s12)
                        .background(RegolithTheme.colors.ground.copy(alpha = 0.7f), PillShape).padding(horizontal = Spacing.s12, vertical = Spacing.s4)
                        .testTag("player_hold_pill"))
            }
            state.error?.let { error ->
                ErrorCard(message = error, testTag = "player_error_card", modifier = Modifier.align(Alignment.BottomCenter).padding(Spacing.s18).systemBarsPadding())
            }
        }
    }

    if (landscape) {
        Box(modifier.fillMaxSize().testTag("player_screen")) { video() }
    } else {
        Column(modifier.fillMaxSize().background(RegolithTheme.colors.ground).testTag("player_screen")) {
            Box(Modifier.fillMaxWidth().statusBarsPadding().aspectRatio(16f / 9f)) { video() }
            PortraitDetails(
                state = state,
                onOpenPlayback = { sheet = Sheet.Playback },
                onLoopTap = { if (state.loop != null) sheet = Sheet.AbLoop else viewModel.tapLoopPoint() },
                onLoopClear = viewModel::clearLoop,
                onPlayNext = viewModel::playNext,
            )
        }
    }

    when (sheet) {
        Sheet.Playback -> PlayerSheetHost(landscape, onDismiss = { sheet = null }, testTag = "player_playback_sheet") {
            PlaybackSheetContent(
                speed = state.speed,
                hardwareDecoding = state.hardwareDecoding,
                scrubThumbnails = scrubThumbnails,
                onSpeed = viewModel::setSpeed,
                onHardwareDecoding = viewModel::setHardwareDecoding,
                onScrubThumbnails = viewModel::setScrubThumbnails,
            )
        }
        Sheet.AbLoop -> state.loop?.let { loop ->
            PlayerSheetHost(landscape, onDismiss = { sheet = null }, testTag = "player_loop_sheet") {
                AbLoopSheetContent(
                    loop = loop,
                    durationMs = state.durationMs,
                    onNudgeA = viewModel::nudgeLoopA,
                    onNudgeB = viewModel::nudgeLoopB,
                    onClear = { viewModel.clearLoop(); sheet = null },
                )
            }
        }
        null -> Unit
    }
}

/** Everything drawn over the picture: top bar, transport, scrubber. */
@Composable
private fun VideoChrome(
    state: PlaybackState,
    landscape: Boolean,
    visible: Boolean,
    scrubPreviewMs: Long?,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onScrubStart: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    onOpenPlayback: () -> Unit,
    onLoopTap: () -> Unit,
    onLoopClear: () -> Unit,
) {
    val colors = RegolithTheme.colors
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).then(if (landscape) Modifier.systemBarsPadding() else Modifier)) {
            // Top: back + title + meta; pills on the right in landscape
            Row(Modifier.fillMaxWidth().padding(Spacing.s8), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("player_back_button")) {
                    Icon(painterResource(LucideR.drawable.lucide_ic_arrow_left), contentDescription = "Back", tint = colors.ink)
                }
                Column(Modifier.weight(1f)) {
                    Text(state.title.uppercase(), style = TextStyles.rowLabel, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (landscape) {
                        val meta = listOf(state.sourceLabel) + (state.video?.chips ?: emptyList())
                        Text(meta.filter { it.isNotEmpty() }.joinToString(" · "), style = TextStyles.metadata, color = colors.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (landscape) {
                    PillRow(state, onOpenPlayback, onLoopTap, onLoopClear)
                }
            }

            // Centre: skip back · play · skip forward
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(Spacing.s30), verticalAlignment = Alignment.CenterVertically) {
                FrostedIcon(LucideR.drawable.lucide_ic_rewind, "Back 10 seconds", 48.dp, onClick = { onSeekBy(-10_000) }, testTag = "player_seek_back_button")
                IconButton(
                    onClick = onTogglePlay,
                    modifier = Modifier.size(if (landscape) 72.dp else 64.dp).clip(CircleShape).background(colors.accent).testTag("player_play_button"),
                ) {
                    Icon(
                        painterResource(if (state.isPlaying) LucideR.drawable.lucide_ic_pause else LucideR.drawable.lucide_ic_play),
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        tint = colors.inkSoft,
                        modifier = Modifier.size(30.dp),
                    )
                }
                FrostedIcon(LucideR.drawable.lucide_ic_fast_forward, "Forward 10 seconds", 48.dp, onClick = { onSeekBy(10_000) }, testTag = "player_seek_forward_button")
            }

            // Bottom: scrubber + times
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = Spacing.s18, vertical = Spacing.s4)) {
                scrubPreviewMs?.let {
                    Text(formatClock(it), style = TextStyles.chip, color = colors.ink,
                        modifier = Modifier.align(Alignment.CenterHorizontally).background(colors.ground.copy(alpha = 0.7f), PillShape).padding(horizontal = Spacing.s8, vertical = Spacing.s2))
                }
                Scrubber(
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    bufferedMs = state.bufferedMs,
                    loop = state.loop,
                    pendingAMs = state.loopPendingAMs,
                    chaptersMs = state.chaptersMs,
                    onScrubStart = onScrubStart,
                    onScrub = onScrub,
                    onScrubEnd = onScrubEnd,
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(formatClock(scrubPreviewMs ?: state.positionMs), style = TextStyles.chip, color = colors.ink, modifier = Modifier.testTag("player_position"))
                    Spacer(Modifier.weight(1f))
                    Text(formatClock(state.durationMs), style = TextStyles.chip, color = colors.body, modifier = Modifier.testTag("player_duration"))
                }
            }
        }
    }
}

@Composable
private fun PillRow(state: PlaybackState, onOpenPlayback: () -> Unit, onLoopTap: () -> Unit, onLoopClear: () -> Unit, onMedia: Boolean = true) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s8), verticalAlignment = Alignment.CenterVertically) {
        PillButton(text = formatSpeed(state.speed), onClick = onOpenPlayback, onMedia = onMedia, testTag = "player_speed_pill")
        PillButton(
            text = if (state.loopPendingAMs != null && state.loop == null) "A ·" else "A–B",
            selected = state.loop != null,
            onClick = onLoopTap,
            onLongClick = onLoopClear,
            onMedia = onMedia,
            icon = LucideR.drawable.lucide_ic_repeat,
            testTag = "player_loop_pill",
        )
        PillButton(text = if (state.hardwareDecoding) "HW" else "SW", onClick = onOpenPlayback, onMedia = onMedia, testTag = "player_decoder_pill")
        if (state.chaptersMs.isNotEmpty()) {
            PillButton(text = "Chapters", onClick = {}, onMedia = onMedia, testTag = "player_chapters_pill")
        }
    }
}

/** Title, chips, pills and "Next in this folder" under the picture in portrait. */
@Composable
private fun PortraitDetails(
    state: PlaybackState,
    onOpenPlayback: () -> Unit,
    onLoopTap: () -> Unit,
    onLoopClear: () -> Unit,
    onPlayNext: (Long) -> Unit,
) {
    val colors = RegolithTheme.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.s18)) {
        Spacer(Modifier.height(Spacing.s18))
        DisplayText(state.title, maxLines = 2)
        Spacer(Modifier.height(Spacing.s8))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
            (state.video?.chips ?: emptyList()).forEach { Chip(it, ChipStyle.OnSurface) }
            if (state.durationMs > 0) Chip(formatDurationShort(state.durationMs), ChipStyle.OnSurface)
            if (state.fileSizeBytes > 0) Chip(formatBytes(state.fileSizeBytes), ChipStyle.OnSurface)
        }
        Spacer(Modifier.height(Spacing.s4))
        Text(state.sourceLabel, style = TextStyles.metadata, color = colors.metadata)
        Spacer(Modifier.height(Spacing.s18))
        PillRow(state, onOpenPlayback, onLoopTap, onLoopClear, onMedia = false)

        if (state.next.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.s30))
            Eyebrow("Next in this folder")
            Spacer(Modifier.height(Spacing.s8))
            state.next.take(10).forEach { item ->
                ListRow(
                    title = item.name,
                    meta = listOfNotNull(item.durationMs?.let { formatDurationShort(it) }, formatBytes(item.sizeBytes)).joinToString(" · "),
                    icon = LucideR.drawable.lucide_ic_film,
                    trailing = RowTrailing.None,
                    onClick = { onPlayNext(item.fileId) },
                    testTag = "player_next_${item.fileId}",
                )
            }
        }
        Spacer(Modifier.height(Spacing.s56))
    }
}

@Composable
private fun FrostedIcon(icon: Int, description: String, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit, testTag: String) {
    val colors = RegolithTheme.colors
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(size).clip(CircleShape).background(colors.surface.copy(alpha = 0.72f)).testTag(testTag),
    ) {
        Icon(painterResource(icon), contentDescription = description, tint = colors.ink, modifier = Modifier.size(22.dp))
    }
}

/** Brightness / volume mid-drag: a white rail where the thumb is, the value in words at the top. No red. */
@Composable
private fun BoxScope.DragRail(drag: DragOverlay) {
    val colors = RegolithTheme.colors
    val muted = drag.kind == DragKind.Volume && drag.fraction <= 0.001f
    val label = when {
        muted -> "Muted"
        drag.kind == DragKind.Brightness -> "Brightness ${(drag.fraction * 100).roundToInt()}%"
        else -> "Volume ${(drag.fraction * 100).roundToInt()}%"
    }
    Text(
        label, style = TextStyles.chip, color = colors.ground,
        modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = Spacing.s12)
            .background(colors.ink, PillShape).padding(horizontal = Spacing.s12, vertical = Spacing.s4).testTag("player_drag_pill"),
    )
    val icon = when {
        muted -> LucideR.drawable.lucide_ic_volume_x
        drag.kind == DragKind.Volume -> LucideR.drawable.lucide_ic_volume_2
        else -> LucideR.drawable.lucide_ic_sun
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.align(if (drag.kind == DragKind.Brightness) Alignment.CenterStart else Alignment.CenterEnd)
            .padding(horizontal = Spacing.s40).fillMaxHeight(0.5f),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = colors.ink, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(Spacing.s8))
        Box(Modifier.weight(1f).width(4.dp).clip(PillShape).background(colors.ink.copy(alpha = 0.25f))) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(drag.fraction.coerceIn(0f, 1f)).background(colors.ink))
        }
    }
}

/** "+20s" / "−10s" over the half that was tapped. */
@Composable
private fun BoxScope.SeekPill(label: String, left: Boolean) {
    val colors = RegolithTheme.colors
    Text(
        label, style = TextStyles.rowLabel, color = colors.ink,
        modifier = Modifier.align(if (left) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = Spacing.s56)
            .background(colors.ground.copy(alpha = 0.7f), PillShape).padding(horizontal = Spacing.s12, vertical = Spacing.s8).testTag("player_seek_pill"),
    )
}
