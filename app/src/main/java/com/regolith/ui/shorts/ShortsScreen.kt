package com.regolith.ui.shorts

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.regolith.R
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.IconCircleButton
import com.regolith.ui.components.LocalNavPillInsets
import com.regolith.ui.components.OrbitArt
import com.regolith.ui.components.ProgressEdge
import com.regolith.ui.components.RegolithSheet
import com.regolith.ui.components.SheetOption
import com.regolith.ui.components.StrataLoader
import com.regolith.ui.player.PlayerSystemControls
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.scaledDp
import kotlinx.coroutines.delay

/**
 * The Shorts feed (design: `design/shorts-canvas/`).
 *
 * Full bleed, one clip per page, swipe up or down to move. Three things to
 * know about how it is put together:
 *
 * It does NOT use `playerGestures`. That detector consumes vertical drags
 * and reports them as fractions — its whole job on the Player screen — so
 * under a [VerticalPager] the two would fight over the same axis. The pager
 * owns vertical, and a plain tap detector owns the rest.
 *
 * The picture is a TextureView, not a SurfaceView. A recycled SurfaceView
 * flickers as pages swap because it goes straight to the compositor and is
 * not part of the view hierarchy's own drawing.
 *
 * And leaving the screen silences it — see the [DisposableEffect] below,
 * which is subtler than it looks.
 */
@UnstableApi
@Composable
fun ShortsScreen(
    viewModel: ShortsViewModel,
    onLocate: (folderId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val bindVersion by viewModel.bindVersion.collectAsStateWithLifecycle()
    val colors = RegolithTheme.colors
    val pager = rememberPagerState { state.items.size }
    var sheetOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    val autoAdvance by viewModel.autoAdvance.collectAsStateWithLifecycle()

    // The player's own brightness/volume helper, not a second one: brightness
    // is a window override here too, so it must be handed back on the way out
    // exactly as PlayerScreen does it.
    val activity = LocalActivity.current
    val system = remember(activity) { PlayerSystemControls(activity) }

    /*
     * Leaving must stop the sound, and there are two ways to leave that look
     * nothing alike.
     *
     * Pushing Browse from Locate keeps this Activity RESUMED — Navigation 3
     * is one Activity and a push is a composition change, not a lifecycle
     * one — so ON_PAUSE never fires and the entry stays in the back stack,
     * which means `onCleared` does not run either. That is why a clip went
     * on playing behind the folder you had just opened. What DOES happen is
     * that this composable leaves composition, so `onDispose` is the hook
     * that catches it.
     *
     * The observer catches the other case, which `onDispose` does not: the
     * app going to the background with the feed still on screen.
     */
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) viewModel.pauseAll()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.pauseAll()
            // A window brightness override outlives the screen that set it.
            system.resetBrightness()
        }
    }

    // settledPage, not currentPage: binding mid-swipe would tear down the
    // player you are still looking at.
    LaunchedEffect(pager.settledPage, state.items) {
        if (state.items.isNotEmpty()) viewModel.bind(pager.settledPage)
    }
    // A new source or a new order is a new feed, and it starts at the top.
    // Without this you would land halfway down a shuffle you just asked for.
    LaunchedEffect(state.folderId, state.shuffled) {
        if (state.items.isNotEmpty()) pager.scrollToPage(0)
    }

    Box(modifier.fillMaxSize().background(colors.ground).testTag("shorts_screen")) {
        if (state.items.isEmpty()) {
            EmptyFeed(state)
        } else {
            VerticalPager(
                state = pager,
                modifier = Modifier.fillMaxSize().testTag("shorts_pager"),
                beyondViewportPageCount = 1,
            ) { page ->
                val item = state.items[page]
                @Suppress("UNUSED_EXPRESSION") bindVersion // recompose once the pool has handed us a player
                ShortPage(
                    item = item,
                    player = viewModel.pool.playerFor(page),
                    shuffled = state.shuffled,
                    filtered = state.folderId != null,
                    autoAdvance = autoAdvance,
                    // Only the clip you are looking at may move the feed. A
                    // prewarmed neighbour wrapping would otherwise scroll the
                    // page out from under a finger that never asked.
                    onWrapped = {
                        if (page == pager.settledPage && page + 1 < state.items.size) {
                            pager.animateScrollToPage(page + 1)
                        }
                    },
                    onAutoAdvance = viewModel::toggleAutoAdvance,
                    onSettings = { settingsOpen = true },
                    onTap = viewModel::togglePlayPause,
                    onHoldFast = viewModel::holdFast,
                    onPickSource = { sheetOpen = true },
                    onShuffle = viewModel::toggleShuffle,
                    onLocate = { onLocate(item.folderId) },
                    onKeep = { viewModel.keepOnDevice(item.fileId) },
                )
            }
        }

        if (settingsOpen) {
            SettingsSheet(system = system, onDismiss = { settingsOpen = false })
        }

        if (sheetOpen) {
            SourceSheet(
                state = state,
                onPick = { folderId ->
                    viewModel.pickFolder(folderId)
                    sheetOpen = false
                },
                onDismiss = { sheetOpen = false },
            )
        }
    }
}

@UnstableApi
@Composable
private fun ShortPage(
    item: ShortItem,
    player: Player?,
    shuffled: Boolean,
    filtered: Boolean,
    autoAdvance: Boolean,
    onWrapped: suspend () -> Unit,
    onTap: () -> Unit,
    onHoldFast: (Boolean) -> Unit,
    onPickSource: () -> Unit,
    onShuffle: () -> Unit,
    onAutoAdvance: () -> Unit,
    onSettings: () -> Unit,
    onLocate: () -> Unit,
    onKeep: () -> Unit,
) {
    val colors = RegolithTheme.colors
    Box(Modifier.fillMaxSize().testTag(item.testTag)) {
        player?.let { ContentFrame(it, Modifier.fillMaxSize(), SURFACE_TYPE_TEXTURE_VIEW, ContentScale.Fit) }

        // Tap anywhere pauses; press and hold the right half runs at 2x.
        // onPress/tryAwaitRelease is the same shape PlayerScreen uses, so
        // "while held" means the same thing on both screens.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onLongPress = { offset: Offset -> if (offset.x > size.width / 2f) onHoldFast(true) },
                        onPress = {
                            tryAwaitRelease()
                            onHoldFast(false)
                        },
                    )
                }
                .testTag("shorts_gesture_layer"),
        )

        if (player == null || player.playbackState == Player.STATE_BUFFERING) {
            StrataLoader(modifier = Modifier.align(Alignment.Center), height = 48.dp, testTag = "shorts_buffering")
        }

        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = Spacing.s18, bottom = LocalNavPillInsets.current.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(Spacing.s18),
        ) {
            IconCircleButton(
                painterResource(R.drawable.rg_ic_library), "Choose which folder to play from", onPickSource,
                "shorts_source_button", onMedia = true, selected = filtered,
            )
            IconCircleButton(
                painterResource(R.drawable.rg_ic_shuffle), if (shuffled) "Shuffling — tap to stop" else "Shuffle these clips",
                onShuffle, "shorts_shuffle_button", onMedia = true, selected = shuffled,
            )
            IconCircleButton(
                painterResource(R.drawable.rg_ic_skip_next),
                if (autoAdvance) "Auto-advance is on — tap to stop" else "Play the next clip automatically",
                onAutoAdvance, "shorts_autoadvance_button", onMedia = true, selected = autoAdvance,
            )
            IconCircleButton(
                painterResource(R.drawable.rg_ic_browse), "Show where this is", onLocate, "shorts_locate_button", onMedia = true,
            )
            IconCircleButton(
                painterResource(if (item.onDevice) R.drawable.rg_ic_check else R.drawable.rg_ic_download),
                if (item.onDevice) "Already on this device" else "Keep on this device",
                onKeep, "shorts_download_button", onMedia = true, selected = item.onDevice,
            )
            IconCircleButton(
                painterResource(R.drawable.rg_ic_sliders), "Sound and brightness", onSettings,
                "shorts_settings_button", onMedia = true,
            )
        }

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = Spacing.s18, end = 96.dp, bottom = LocalNavPillInsets.current.calculateBottomPadding()),
        ) {
            Text(item.name, style = TextStyles.settingLabel, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(item.folderLabel, item.meta).filter { it.isNotEmpty() }.joinToString(" · "),
                style = TextStyles.meta, color = colors.body, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }

        // Where it has got to, and nothing more: scrubbing is deliberately
        // not a thing you can do to a clip this short.
        /*
         * One poll, two jobs. The bar needs the position anyway, and with
         * REPEAT_MODE_ONE the clip NEVER reaches STATE_ENDED — ExoPlayer
         * seeks back to zero internally and the playback state never changes
         * — so there is no event to listen for. A loop shows up as the
         * position jumping BACKWARDS, which is what is watched for here.
         * Looping therefore stays the fallback when auto-advance is off, and
         * when it is on the clip still plays whole before the feed moves.
         */
        val fraction by produceState(0f, player, autoAdvance) {
            var last = 0L
            while (true) {
                val p = player
                val position = p?.currentPosition ?: 0L
                value = if (p != null && p.duration > 0) (position.toFloat() / p.duration).coerceIn(0f, 1f) else 0f
                if (autoAdvance && p != null && position + WRAP_SLACK_MS < last) onWrapped()
                last = position
                delay(250)
            }
        }
        ProgressEdge(fraction, Modifier.align(Alignment.BottomCenter).fillMaxWidth().testTag("shorts_progress"))
    }
}

/** "Play from": everywhere, or one folder. Only folders that hold shorts are offered. */
@Composable
private fun SourceSheet(state: ShortsUiState, onPick: (Long?) -> Unit, onDismiss: () -> Unit) {
    val total = state.folders.sumOf { it.count }
    RegolithSheet(
        title = "Play from",
        onDismiss = onDismiss,
        testTag = "shorts_folder_sheet",
        subtitle = if (state.folders.size == 1) "One folder holds shorts" else "${state.folders.size} folders hold shorts",
    ) {
        SheetOption(
            label = ShortsUiState.EVERYWHERE,
            selected = state.folderId == null,
            onClick = { onPick(null) },
            testTag = "shorts_folder_all",
            trailing = if (total == 1) "1 clip" else "$total clips",
        )
        state.folders.forEach { folder ->
            SheetOption(
                label = folder.label,
                selected = state.folderId == folder.id,
                onClick = { onPick(folder.id) },
                testTag = folder.testTag,
                trailing = if (folder.count == 1) "1 clip" else "${folder.count} clips",
            )
        }
    }
}

/**
 * Sound and brightness, because the feed cannot carry the player's edge
 * drags: the pager owns the vertical axis, so there is nowhere to put them.
 *
 * Discrete steps rather than a slider — the app has no slider, and the
 * playback sheet already picks its speed from a row of pills, so this is
 * that control rather than a new kind of one.
 */
@Composable
private fun SettingsSheet(system: PlayerSystemControls, onDismiss: () -> Unit) {
    // Read once on open: the volume is the real media stream and the
    // brightness a window override, so both have a value before we touch them.
    var volume by remember { mutableFloatStateOf(system.volume()) }
    var brightness by remember { mutableFloatStateOf(system.brightness()) }
    RegolithSheet(title = "Sound and light", onDismiss = onDismiss, testTag = "shorts_settings_sheet") {
        Eyebrow("Volume", muted = true)
        LevelRow(volume, "shorts_volume") {
            volume = it
            system.setVolume(it)
        }
        Spacer(Modifier.size(Spacing.s18))
        Eyebrow("Brightness", muted = true)
        LevelRow(brightness, "shorts_brightness") {
            brightness = it
            system.setBrightness(it)
        }
        Spacer(Modifier.size(Spacing.s18))
    }
}

/** The playback sheet's speed row, holding a level instead of a speed. */
@Composable
private fun LevelRow(value: Float, tagPrefix: String, onPick: (Float) -> Unit) {
    val colors = RegolithTheme.colors
    Row(Modifier.padding(top = Spacing.s8), horizontalArrangement = Arrangement.spacedBy(Spacing.s4)) {
        LEVELS.forEach { level ->
            // Nearest step wins, so a level set by anything else still lights one.
            val selected = LEVELS.minByOrNull { kotlin.math.abs(it - value) } == level
            Box(
                Modifier.weight(1f).height(36.scaledDp()).clip(PillShape)
                    .background(if (selected) colors.accent else colors.frostBg)
                    .then(if (selected) Modifier else Modifier.border(1.dp, colors.frostBorder, PillShape))
                    .clickable(interactionSource = null, indication = null) { onPick(level) }
                    .testTag("${tagPrefix}_${(level * 100).toInt()}"),
                contentAlignment = Alignment.Center,
            ) {
                Text("${(level * 100).toInt()}%", style = TextStyles.buttonSmall, color = if (selected) colors.ink else colors.inkSoft)
            }
        }
    }
}

/** Five steps, like the speed row. Brightness never reaches 0: a black screen is not a setting. */
private val LEVELS = listOf(0.01f, 0.25f, 0.5f, 0.75f, 1f)

/** A backwards jump bigger than one poll means the clip looped, not that it drifted. */
private const val WRAP_SLACK_MS = 400L

/** Nothing to show — and whether that is "none" or "not yet" is the whole message. */
@Composable
private fun EmptyFeed(state: ShortsUiState) {
    val colors = RegolithTheme.colors
    Column(
        Modifier.fillMaxSize().padding(horizontal = Spacing.s40).testTag("shorts_empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        OrbitArt()
        Spacer(Modifier.size(Spacing.s30))
        DisplayText(
            if (state.measuringLine != null) "STILL MEASURING YOUR SHARE" else "NOTHING VERTICAL YET",
            style = TextStyles.emptyTitle,
            color = colors.ink,
        )
        Spacer(Modifier.size(Spacing.s12))
        Text(
            state.measuringLine?.let { "Clips show up here as each file is measured. Nothing extra to run." }
                ?: "Shorts collects videos that are taller than they are wide and a minute or less.",
            style = TextStyles.body, color = colors.body,
        )
        state.measuringLine?.let { line ->
            Spacer(Modifier.size(Spacing.s30))
            ProgressEdge(state.measuringFraction, Modifier.fillMaxWidth().testTag("shorts_measuring_bar"))
            Spacer(Modifier.size(Spacing.s12))
            Text(line.uppercase(), style = TextStyles.eyebrow, color = colors.metadata, modifier = Modifier.testTag("shorts_measuring_line"))
        }
    }
}
