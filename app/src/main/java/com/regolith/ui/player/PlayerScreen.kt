package com.regolith.ui.player

import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import com.composables.icons.lucide.R as LucideR
import com.regolith.ui.components.ErrorCard
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.util.formatClock
import kotlinx.coroutines.delay

/**
 * The player (design section 10), Phase 1 subset: picture, tap to show
 * controls, play/pause, a scrub bar with times, back. Landscape and
 * immersive (no system bars). Everything else in section 10 is Phase 2.
 *
 * Immersive mode and orientation are Activity-level settings, so they are
 * applied in a DisposableEffect and undone when the screen leaves.
 */
@UnstableApi
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(activity) {
        val window = activity?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Save progress when the app goes to the background mid-playback.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) viewModel.onPause() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var controlsVisible by remember { mutableStateOf(true) }
    // Controls auto-hide 3 s after the last interaction while playing.
    LaunchedEffect(controlsVisible, state.isPlaying) {
        if (controlsVisible && state.isPlaying) {
            delay(3_000)
            controlsVisible = false
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                controlsVisible = !controlsVisible
            }
            .testTag("player_screen"),
    ) {
        PlayerSurface(
            player = viewModel.player,
            modifier = Modifier.fillMaxSize(),
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
        )

        if (state.isBuffering) {
            CircularProgressIndicator(
                color = RegolithTheme.colors.accent,
                trackColor = Color.Transparent,
                modifier = Modifier.align(Alignment.Center).size(48.dp).testTag("player_buffering"),
            )
        }

        AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut()) {
            Controls(
                title = state.title,
                isPlaying = state.isPlaying,
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                onBack = onBack,
                onTogglePlay = { viewModel.togglePlayPause(); controlsVisible = true },
                onSeek = { viewModel.seekTo(it); controlsVisible = true },
            )
        }

        state.error?.let { error ->
            ErrorCard(
                message = error,
                testTag = "player_error_card",
                modifier = Modifier.align(Alignment.BottomCenter).padding(Spacing.s18).systemBarsPadding(),
            )
        }
    }
}

@Composable
private fun Controls(
    title: String,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val colors = RegolithTheme.colors
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).systemBarsPadding()) {
        // Top: back + title
        Row(Modifier.fillMaxWidth().padding(Spacing.s8), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("player_back_button")) {
                Icon(painterResource(LucideR.drawable.lucide_ic_arrow_left), contentDescription = "Back", tint = colors.ink)
            }
            Spacer(Modifier.width(Spacing.s4))
            Text(title, style = TextStyles.rowLabel, color = colors.ink, maxLines = 1)
        }

        // Centre: play/pause on a frosted circle
        IconButton(
            onClick = onTogglePlay,
            modifier = Modifier
                .align(Alignment.Center)
                .size(72.dp)
                .clip(CircleShape)
                .background(colors.surface.copy(alpha = 0.72f))
                .testTag("player_play_button"),
        ) {
            Icon(
                painterResource(if (isPlaying) LucideR.drawable.lucide_ic_pause else LucideR.drawable.lucide_ic_play),
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = colors.ink,
                modifier = Modifier.size(32.dp),
            )
        }

        // Bottom: scrub bar + times
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = Spacing.s18, vertical = Spacing.s12)) {
            var dragging by remember { mutableStateOf(false) }
            var dragValue by remember { mutableFloatStateOf(0f) }
            val fraction = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
            Slider(
                value = if (dragging) dragValue else fraction,
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = {
                    dragging = false
                    onSeek((dragValue * durationMs).toLong())
                },
                colors = SliderDefaults.colors(
                    thumbColor = colors.ink,
                    activeTrackColor = colors.accent,
                    inactiveTrackColor = colors.ink.copy(alpha = 0.25f),
                ),
                modifier = Modifier.fillMaxWidth().testTag("player_scrubber"),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatClock(if (dragging) (dragValue * durationMs).toLong() else positionMs), style = TextStyles.chip, color = colors.ink, modifier = Modifier.testTag("player_position"))
                Spacer(Modifier.weight(1f))
                Text(formatClock(durationMs), style = TextStyles.chip, color = colors.body, modifier = Modifier.testTag("player_duration"))
            }
            Spacer(Modifier.height(Spacing.s4))
        }
    }
}
