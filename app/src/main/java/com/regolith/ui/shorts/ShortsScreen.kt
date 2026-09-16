package com.regolith.ui.shorts

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.regolith.ui.components.IconCircleButton
import com.regolith.ui.components.LocalNavPillInsets
import com.regolith.ui.components.OrbitArt
import com.regolith.ui.components.ProgressEdge
import com.regolith.ui.components.RegolithSheet
import com.regolith.ui.components.SheetOption
import com.regolith.ui.components.StrataLoader
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
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
                    onTap = viewModel::togglePlayPause,
                    onHoldFast = viewModel::holdFast,
                    onPickSource = { sheetOpen = true },
                    onShuffle = viewModel::toggleShuffle,
                    onLocate = { onLocate(item.folderId) },
                    onKeep = { viewModel.keepOnDevice(item.fileId) },
                )
            }
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
    onTap: () -> Unit,
    onHoldFast: (Boolean) -> Unit,
    onPickSource: () -> Unit,
    onShuffle: () -> Unit,
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
                painterResource(R.drawable.rg_ic_browse), "Show where this is", onLocate, "shorts_locate_button", onMedia = true,
            )
            IconCircleButton(
                painterResource(if (item.onDevice) R.drawable.rg_ic_check else R.drawable.rg_ic_download),
                if (item.onDevice) "Already on this device" else "Keep on this device",
                onKeep, "shorts_download_button", onMedia = true, selected = item.onDevice,
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
        val fraction by produceState(0f, player) {
            while (true) {
                val p = player
                value = if (p != null && p.duration > 0) (p.currentPosition.toFloat() / p.duration).coerceIn(0f, 1f) else 0f
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
