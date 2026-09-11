package com.regolith.ui.player

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.compose.BackHandler
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import com.regolith.R
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.domain.playback.SeekStacker
import com.regolith.player.PlaybackState
import com.regolith.domain.playback.PlayerOrientation
import com.regolith.player.NextItem
import com.regolith.ui.components.ArtworkImage
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.ErrorCard
import com.regolith.ui.components.PrimaryButton
import com.regolith.ui.components.SecondaryButton
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.PillButton
import com.regolith.ui.components.Scrubber
import com.regolith.ui.theme.CardShape
import com.regolith.ui.theme.PillShape
import com.regolith.ui.adaptive.FoldPosture
import com.regolith.ui.adaptive.LocalWindowShape
import com.regolith.ui.theme.RegolithTheme
import coil3.compose.SubcomposeAsyncImageContent
import coil3.compose.SubcomposeAsyncImage
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import com.regolith.ui.theme.ThumbShape
import kotlin.math.abs
import kotlin.math.absoluteValue
import androidx.compose.ui.platform.LocalDensity
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.designSp
import com.regolith.ui.util.formatBytes
import com.regolith.ui.util.formatClock
import com.regolith.ui.util.formatDurationShort
import com.regolith.ui.util.formatSpeed
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private enum class Sheet { Playback, AbLoop, Chapters }
private enum class DragKind { Brightness, Volume }
private data class DragOverlay(val kind: DragKind, val fraction: Float, val xFraction: Float)

/**
 * The player (design section 10). Full screen is the engine: immersive,
 * every control on the picture, 18/30/12 padding. The windowed layout is
 * the same engine in a 16:9 strip with fewer controls on the picture and
 * the title, meta line, pills and "Next in this folder" underneath.
 *
 * Two separate things decide which one you get:
 *  - the phone's rotation, which the player always follows (landscape is
 *    always full screen: there is nothing else a wide screen should do);
 *  - the full-screen button, which fills the screen *in the orientation
 *    you are already in*. It never rotates the phone.
 * So the button is a portrait-only control; in landscape the picture is
 * already full-bleed and turning the phone back is what leaves it.
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
    val scrubFrame by viewModel.scrubFrame.collectAsStateWithLifecycle()
    val chapterFrames by viewModel.chapterFrames.collectAsStateWithLifecycle()
    val gesturesSeen by viewModel.gesturesSeen.collectAsStateWithLifecycle()
    val orientation by viewModel.orientation.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val system = remember(activity) { PlayerSystemControls(activity) }
    // Fills the screen without touching the orientation. Landscape is
    // full-bleed either way, so this only means anything in portrait.
    var fullscreen by remember { mutableStateOf(false) }
    // Half open on a table, hinge across: the picture takes the top half and
    // the controls the bottom, so nothing has to be held. This wins over
    // full screen — there is no picture worth filling a folded screen with.
    val windowShape = LocalWindowShape.current
    val hinge = windowShape.hinge
    val flex = windowShape.posture == FoldPosture.TABLE_TOP && hinge != null
    // A wide window turned sideways is not a reason to fill it. On a phone,
    // landscape IS full screen — there is nothing else 411dp of height can
    // usefully hold. Unfolded, or on a tablet, there is room for the picture
    // AND the folder beside it, so the sideways layout becomes the two-pane
    // one and full screen goes back to being something you ask for.
    val wide = windowShape.wide
    val forcedFullscreen = landscape && !wide
    val immersive = !flex && (forcedFullscreen || fullscreen)
    // Video left, the folder in a column on the right (F9). This is what a
    // wide window turned sideways ALWAYS does — it used to also require the
    // folder to have something in it, which meant the last episode of a
    // season, or any lone file, opened into the portrait layout instead. The
    // layout is a property of the window, not of what happens to be next.
    // Only a film handed over by another app stands the column down, because
    // it has no folder to put there at all.
    val sideBySide = !flex && !immersive && wide && landscape && state.fileId != null

    // Follow the phone's rotation; hide the system bars whenever the picture fills the screen.
    DisposableEffect(activity, immersive, flex, orientation) {
        val window = activity?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        // The rotation lock. AUTO is what the player has always done; the two
        // locks pin the Activity, which is why `landscape` below (and so the
        // immersive layout) simply follows — a locked landscape player is
        // full-bleed for the same reason a turned phone is.
        activity.requestedOrientation = when (orientation) {
            PlayerOrientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            PlayerOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            PlayerOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        if (immersive || flex) {
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

    // Wherever full screen was a choice, back un-chooses it before it leaves the player.
    BackHandler(enabled = fullscreen && !forcedFullscreen) { fullscreen = false }

    // Save progress when the app goes to the background mid-playback.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) viewModel.onPause() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // --- Up next (F6). Four things have to be true before the app plays on
    // by itself: you left the setting on, the film actually ran to its end
    // (playWhenReady is still set, so scrubbing to the last second while
    // paused is not an ending), the folder has another file, and you have
    // not already said no to this one.
    val autoplayNext by viewModel.autoplayNext.collectAsStateWithLifecycle()
    val autoplayImmediately by viewModel.autoplayImmediately.collectAsStateWithLifecycle()
    val upNext = state.next.firstOrNull()
    var autoplayCancelled by remember(state.fileId) { mutableStateOf(false) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    // An explicit queue beats the setting: tapping Play all or Shuffle on a
    // folder of seven is a request for all seven, and a queue that stopped
    // after the first would be a bug in any other player. "Keep playing"
    // governs what happens when you open ONE file and it ends.
    val autoplayArmed = (autoplayNext || state.queued) && !autoplayCancelled && upNext != null && state.ended && state.playWhenReady
    LaunchedEffect(autoplayArmed, upNext?.fileId) {
        countdown = null
        if (!autoplayArmed || upNext == null) return@LaunchedEffect
        // repeatOnLifecycle, not a bare loop: a coroutine launched from the
        // composition keeps running when the app goes to the background, and
        // a film that ends off-screen must not quietly pull the next one off
        // the share. The count starts — and restarts — when the screen is
        // actually in front of you.
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                // "Don't ask first": no card, no ring, the next file simply
                // starts. The lifecycle gate still applies — the point of it
                // is not the countdown but that you are there to see it.
                if (!autoplayImmediately) {
                    for (second in AUTOPLAY_SECONDS downTo 1) {
                        countdown = second
                        delay(1_000)
                    }
                }
                viewModel.playNext(upNext.fileId)
            } finally {
                countdown = null
            }
        }
    }

    // --- The middle-third drag, made visible (F7). The gesture already knew
    // how far your finger had travelled; nothing on screen did, so there was
    // no way to tell it was working or how far was far enough.
    //
    // Signed: negative is up (toward full screen), positive is down (out of
    // full screen, or out of the player). `snap` while the finger is down so
    // the picture tracks it exactly, a spring on release so an abandoned drag
    // settles rather than jumping.
    var middleDrag by remember { mutableFloatStateOf(0f) }
    var middleDragging by remember { mutableStateOf(false) }
    val middleT by animateFloatAsState(
        targetValue = middleDrag,
        animationSpec = if (middleDragging) snap() else spring(dampingRatio = 0.8f, stiffness = 500f),
        label = "playerMiddleDrag",
    )
    // 0..1 of the way to committing, in each direction.
    val dragUp = (-middleT / FULLSCREEN_DRAG_FRACTION).coerceIn(0f, 1f)
    val dragDown = (middleT / FULLSCREEN_DRAG_FRACTION).coerceIn(0f, 1f)
    // Scale is the one transform a SurfaceView actually honours from a parent
    // graphicsLayer — measured on the Fold: a 0.6 scale moved the picture,
    // a 0.25 alpha did nothing at all. So the picture grows and shrinks, and
    // everything that needs to FADE (the details, the ground) is either an
    // ordinary composable or a scrim drawn over the top.
    val pictureScale = 1f + dragUp * 0.16f - dragDown * 0.14f
    val haptics = LocalHapticFeedback.current
    // One tick as you cross the point of no return, so you can feel that
    // letting go now will do something.
    val past = dragUp >= 1f || dragDown >= 1f
    LaunchedEffect(past) { if (past) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }

    var controlsVisible by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    // A chapter list belongs to one file. Autoplay can change the file while
    // the sheet is open, and a list of the last film's chapters over the next
    // one is worse than no list.
    LaunchedEffect(state.fileId) { if (sheet == Sheet.Chapters) sheet = null }
    // Pictures cost a key-frame seek each, so they are only asked for once
    // the sheet is actually open — and again if the film changes under it.
    LaunchedEffect(sheet, state.fileId, state.durationMs) {
        if (sheet == Sheet.Chapters) viewModel.requestChapterFrames()
    }
    var fill by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf<DragOverlay?>(null) }
    var seekLabel by remember { mutableStateOf<String?>(null) }
    var scrubPreviewMs by remember { mutableStateOf<Long?>(null) }
    val stacker = remember { SeekStacker() }
    val showGestureMap = gesturesSeen == false

    // Controls auto-hide 3 s after the last interaction while playing, never mid-scrub.
    LaunchedEffect(controlsVisible, state.playWhenReady, sheet, scrubPreviewMs != null) {
        if (controlsVisible && state.playWhenReady && sheet == null && scrubPreviewMs == null) {
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

    // Only a windowed layout has a full screen to enter or leave: a phone in
    // landscape is already full-bleed, and flex mode is the hinge's layout,
    // not a choice.
    val canToggleFullscreen = !forcedFullscreen && !flex
    val gestures = remember(viewModel, system, canToggleFullscreen, fullscreen) {
        object : PlayerGestureCallbacks {
            private var dragValue = 0f
            private var zone: Zone? = null
            private var middleDy = 0f
            override fun onTap(zone: Zone) {
                if (zone == Zone.MIDDLE) {
                    controlsVisible = !controlsVisible
                    return
                }
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
                // The middle is neither the back nor the forward side, so it
                // cannot seek; play/pause is the gesture that belongs there.
                if (zone == Zone.MIDDLE) {
                    viewModel.togglePlayPause()
                    return
                }
                val now = System.currentTimeMillis()
                viewModel.seekBy(stacker.onDoubleTap(if (zone == Zone.LEFT) -1 else 1, now))
                seekLabel = stacker.pendingLabel(now)
            }
            override fun onLongPressStart() = viewModel.holdFast(true)
            override fun onPressReleased() { if (state.holdingFast) viewModel.holdFast(false) }
            override fun onDragStart(zone: Zone, xFraction: Float) {
                this.zone = zone
                controlsVisible = false
                if (zone == Zone.MIDDLE) {
                    // No rail: the picture itself is the readout.
                    middleDy = 0f
                    middleDragging = true
                    middleDrag = 0f
                    return
                }
                val kind = if (zone == Zone.LEFT) DragKind.Brightness else DragKind.Volume
                dragValue = if (kind == DragKind.Brightness) system.brightness() else system.volume()
                drag = DragOverlay(kind, dragValue, xFraction)
            }
            override fun onDrag(dyFraction: Float) {
                if (zone == Zone.MIDDLE) {
                    middleDy += dyFraction
                    middleDrag = middleDy
                    return
                }
                val d = drag ?: return
                dragValue = (dragValue - dyFraction * 1.5f).coerceIn(0f, 1f)
                if (d.kind == DragKind.Brightness) system.setBrightness(dragValue) else system.setVolume(dragValue)
                drag = d.copy(fraction = dragValue)
            }
            override fun onDragEnd(flingDown: Boolean) {
                val ended = zone
                zone = null
                drag = null
                // Whatever happens next, the picture springs back to rest: a
                // committed drag is followed by a layout change, and an
                // abandoned one has to undo itself visibly.
                middleDragging = false
                middleDrag = 0f
                // Only the middle third dismisses or resizes. A fast brightness
                // drag used to close the film, because any fling down did.
                if (ended != Zone.MIDDLE) return
                val down = middleDy > 0f
                val committed = abs(middleDy) > FULLSCREEN_DRAG_FRACTION || flingDown
                middleDy = 0f
                if (!committed) return
                when {
                    !canToggleFullscreen -> if (down) onBack()
                    down && fullscreen -> fullscreen = false
                    down -> onBack()
                    !fullscreen -> fullscreen = true
                }
            }
            override fun onZoom(factor: Float) {
                if (factor > 1.02f) fill = true else if (factor < 0.98f) fill = false
            }
        }
    }

    val chromeCallbacks = ChromeCallbacks(
        // Back is "step out one level": out of portrait full screen first,
        // out of the player only when there is no full screen to leave.
        onBack = { if (fullscreen && !forcedFullscreen) fullscreen = false else onBack() },
        onTogglePlay = { viewModel.togglePlayPause(); controlsVisible = true },
        onSeekBy = { viewModel.seekBy(it); controlsVisible = true },
        onScrubStart = { scrubPreviewMs = state.positionMs; viewModel.onScrub(state.positionMs) },
        onScrub = { f -> val ms = (f * state.durationMs).toLong(); scrubPreviewMs = ms; viewModel.onScrub(ms) },
        onScrubEnd = { f -> scrubPreviewMs = null; viewModel.onScrubEnd(); viewModel.seekTo((f * state.durationMs).toLong()); controlsVisible = true },
        onOpenPlayback = { sheet = Sheet.Playback },
        onLoopTap = { if (state.loop != null) sheet = Sheet.AbLoop else viewModel.tapLoopPoint() },
        onLoopClear = viewModel::clearLoop,
        onFullscreen = { fullscreen = !fullscreen },
        // Null greys the button out rather than removing it: a transport row
        // that changes width as you walk a folder is worse than a dead key.
        onPrevious = state.previous?.let { p -> { viewModel.playNext(p.fileId) } },
        onNext = upNext?.let { n -> { viewModel.playNext(n.fileId) } },
    )

    // The playback sheet's own content, inline rather than behind a pill.
    // Wide layouts have the room; the phone never composes this.
    val playbackSettings = @Composable {
        PlaybackSheetContent(
            speed = state.speed,
            hardwareDecoding = state.hardwareDecoding,
            scrubThumbnails = scrubThumbnails,
            autoplayNext = autoplayNext,
            autoplayImmediately = autoplayImmediately,
            orientation = orientation,
            onSpeed = viewModel::setSpeed,
            onOrientation = viewModel::setOrientation,
            onHardwareDecoding = viewModel::setHardwareDecoding,
            onScrubThumbnails = viewModel::setScrubThumbnails,
            onAutoplayNext = viewModel::setAutoplayNext,
            onAutoplayImmediately = viewModel::setAutoplayImmediately,
            onClose = {},
            header = false,
        )
    }

    val video = @Composable {
        // Never its own ground: whatever the picture does not cover is the
        // layout's to fill, which is how the letterbox bars pick up the glow
        // instead of being black.
        Box(Modifier.fillMaxSize()) {
            player?.let { p ->
                ContentFrame(p, Modifier.fillMaxSize(), SURFACE_TYPE_SURFACE_VIEW, if (fill) ContentScale.Crop else ContentScale.Fit)
            }
            Box(Modifier.fillMaxSize().playerGestures(gestures).testTag("player_gesture_layer"))
            if (state.isBuffering) {
                CircularProgressIndicator(color = RegolithTheme.colors.accent, trackColor = Color.Transparent, strokeWidth = 2.dp, modifier = Modifier.align(Alignment.Center).size(48.dp).testTag("player_buffering"))
            }
            if (flex) {
                // Above the fold there is only the header: the timeline and the
                // transport live in the deck below, where the hands are.
                FlexChrome(state, controlsVisible && drag == null, chromeCallbacks)
            } else if (immersive) {
                // The collapse glyph only appears when full screen was a
                // choice; in landscape it is the rotation, so there is
                // nothing for a button to undo.
                FullChrome(state, controlsVisible && drag == null, scrubPreviewMs, scrubFrame, chromeCallbacks, canCollapse = !forcedFullscreen)
            } else {
                PortraitChrome(state, controlsVisible && drag == null, scrubPreviewMs, scrubFrame, chromeCallbacks)
            }
            if (state.loop != null && !immersive) LoopingPill(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 14.dp, top = 10.dp))
            drag?.let { DragRail(it) }
            seekLabel?.let { SeekPill(it, left = it.startsWith("−")) }
            if (state.holdingFast) {
                OnMediaLabel("2× while held", Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 78.dp).testTag("player_hold_pill"))
            }
            state.error?.let { error ->
                ErrorCard(message = error, testTag = "player_error_card", modifier = Modifier.align(Alignment.BottomCenter).padding(Spacing.s18).systemBarsPadding())
            }
            // Over the ended frame, in every layout: the picture is finished,
            // so there is nothing underneath worth keeping clear.
            countdown?.let { seconds ->
                if (upNext != null) {
                    Box(Modifier.fillMaxSize().background(Color(0x99000000)))
                    UpNextCard(
                        item = upNext,
                        seconds = seconds,
                        onPlayNow = { viewModel.playNext(upNext.fileId) },
                        onCancel = { autoplayCancelled = true },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(Spacing.s18).systemBarsPadding(),
                    )
                }
            }
        }
    }

    if (flex) {
        // The split is the hinge's own position, read from the device, not
        // half the screen: the two halves of a fold are not exactly equal.
        val topHeight = with(LocalDensity.current) { hinge!!.top.toDp() }
        val strip by viewModel.strip.collectAsStateWithLifecycle()
        // Ask for the strip once the deck is on screen, and again if the film changes.
        LaunchedEffect(state.durationMs, scrubThumbnails) {
            if (scrubThumbnails && state.durationMs > 0) viewModel.requestStrip()
        }
        Column(modifier.fillMaxSize().background(Color.Black).testTag("player_screen")) {
            Box(Modifier.fillMaxWidth().height(topHeight)) {
                AmbientGlow(state.fileId, Modifier.fillMaxSize())
                video()
            }
            FlexDeck(
                state = state,
                strip = strip,
                scrubPreviewMs = scrubPreviewMs,
                cb = chromeCallbacks,
                onPlayNext = viewModel::playNext,
                modifier = Modifier.fillMaxWidth().weight(1f).background(RegolithTheme.colors.ground),
            )
        }
    } else if (immersive) {
        Box(modifier.fillMaxSize().background(Color.Black).testTag("player_screen")) {
            // Ambient bars (F10). A 2.39:1 film in a 16:9 window, or any film
            // on the near-square inner display, leaves bands the picture does
            // not reach; media3 sizes the video surface to the CONTENT, so
            // those bands belong to us and can carry the film's own colour
            // instead of black. Bars burned into the frames themselves are a
            // different thing and stay exactly as they are — those pixels are
            // the picture. Black stays underneath, so a film whose poster
            // never loaded looks the way it always did.
            AmbientGlow(state.fileId, Modifier.fillMaxSize(), spill = true)
            Box(Modifier.fillMaxSize().graphicsLayer { scaleX = pictureScale; scaleY = pictureScale }) { video() }
            // A SurfaceView ignores alpha from a parent layer, so "dimming"
            // is a scrim drawn over it rather than a fade applied to it.
            if (dragDown > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dragDown * 0.45f)))
            // The map's three columns need the width; it stays a landscape lesson.
            if (showGestureMap && landscape) GestureMap(onDismiss = viewModel::dismissGestureMap)
        }
    } else if (sideBySide) {
        // Two columns, level at the top. The left one is the film with its
        // own controls under it — the same order the portrait player reads
        // in — and the right one is the folder, so what plays next sits
        // beside the picture rather than below a screenful of settings.
        val sideWidth = (windowShape.width * SIDE_COLUMN_FRACTION).coerceIn(300.dp, 460.dp)
        Box(modifier.fillMaxSize().background(RegolithTheme.colors.ground).testTag("player_screen")) {
            AmbientGlow(state.fileId, Modifier.fillMaxSize())
            Row(Modifier.fillMaxSize().systemBarsPadding()) {
                Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = Spacing.s12)) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                            .graphicsLayer { scaleX = pictureScale; scaleY = pictureScale },
                    ) { video() }
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState())
                            .padding(top = Spacing.s18, start = Spacing.s8, end = Spacing.s8)
                            .graphicsLayer { alpha = 1f - maxOf(dragUp, dragDown) }
                            .testTag(if (state.loop != null) "player_loop_panel" else "player_details"),
                        verticalArrangement = Arrangement.spacedBy(Spacing.s18),
                    ) {
                        val loop = state.loop
                        if (loop != null) {
                            AbLoopSheetContent(
                                loop = loop, positionMs = state.positionMs, durationMs = state.durationMs,
                                onNudgeA = viewModel::nudgeLoopA, onNudgeB = viewModel::nudgeLoopB,
                                onClear = viewModel::clearLoop,
                            )
                        } else {
                            TitleBlock(state)
                            // Speed and decoder are the panel below; a pill that
                            // opened a sheet saying the same thing would be a
                            // second control for one setting. A–B and Chapters
                            // stay: neither has another entry.
                            PillRow(
                                state, { sheet = Sheet.Playback }, viewModel::tapLoopPoint, viewModel::clearLoop,
                                onMedia = false, settingsPills = false, onChapters = { sheet = Sheet.Chapters },
                            )
                            playbackSettings()
                        }
                        Spacer(Modifier.height(Spacing.s30))
                    }
                }
                Column(
                    Modifier.width(sideWidth).fillMaxHeight().verticalScroll(rememberScrollState())
                        .padding(end = Spacing.s18, top = Spacing.s12, bottom = Spacing.s12)
                        .graphicsLayer { alpha = 1f - maxOf(dragUp, dragDown) }
                        .testTag("player_up_next_column"),
                ) {
                    NextInFolder(state, viewModel::playNext, emptyState = true)
                    Spacer(Modifier.height(Spacing.s30))
                }
            }
        }
    } else {
        Box(modifier.fillMaxSize().background(RegolithTheme.colors.ground).testTag("player_screen")) {
        AmbientGlow(state.fileId, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().statusBarsPadding().aspectRatio(16f / 9f).graphicsLayer { scaleX = pictureScale; scaleY = pictureScale }) { video() }
            PortraitDetails(
                modifier = Modifier.graphicsLayer {
                    // Up: the details get out of the picture's way. Down: they
                    // go with it, so the whole player reads as one thing being
                    // put away rather than a picture shrinking on a live page.
                    alpha = 1f - maxOf(dragUp, dragDown)
                    translationY = dragUp * 60.dp.toPx()
                    scaleX = 1f - dragDown * 0.06f
                    scaleY = 1f - dragDown * 0.06f
                },
                state = state,
                onOpenPlayback = { sheet = Sheet.Playback },
                onLoopTap = viewModel::tapLoopPoint,
                onLoopClear = viewModel::clearLoop,
                onNudgeA = viewModel::nudgeLoopA,
                onNudgeB = viewModel::nudgeLoopB,
                onPlayNext = viewModel::playNext,
                onChapters = { sheet = Sheet.Chapters },
                settings = playbackSettings,
            )
        }
        }
    }

    when (sheet) {
        Sheet.Playback -> PlayerSheetHost(onDismiss = { sheet = null }, testTag = "player_playback_sheet") {
            PlaybackSheetContent(
                speed = state.speed,
                hardwareDecoding = state.hardwareDecoding,
                scrubThumbnails = scrubThumbnails,
                autoplayNext = autoplayNext,
                autoplayImmediately = autoplayImmediately,
                orientation = orientation,
                onSpeed = viewModel::setSpeed,
                onOrientation = viewModel::setOrientation,
                onHardwareDecoding = viewModel::setHardwareDecoding,
                onScrubThumbnails = viewModel::setScrubThumbnails,
                onAutoplayNext = viewModel::setAutoplayNext,
                onAutoplayImmediately = viewModel::setAutoplayImmediately,
                onClose = { sheet = null },
            )
        }
        Sheet.Chapters -> PlayerSheetHost(onDismiss = { sheet = null }, testTag = "player_chapters_sheet") {
            ChaptersSheetContent(
                chapters = state.chapters,
                frames = chapterFrames,
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                fromContainer = state.chaptersFromContainer,
                onSeek = { ms -> viewModel.seekTo(ms); sheet = null },
            )
        }
        Sheet.AbLoop -> state.loop?.let { loop ->
            PlayerSheetHost(onDismiss = { sheet = null }, testTag = "player_loop_sheet") {
                AbLoopSheetContent(
                    loop = loop,
                    positionMs = state.positionMs,
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

private class ChromeCallbacks(
    val onBack: () -> Unit,
    val onTogglePlay: () -> Unit,
    val onSeekBy: (Long) -> Unit,
    val onScrubStart: () -> Unit,
    val onScrub: (Float) -> Unit,
    val onScrubEnd: (Float) -> Unit,
    val onOpenPlayback: () -> Unit,
    val onLoopTap: () -> Unit,
    val onLoopClear: () -> Unit,
    val onFullscreen: () -> Unit,
    val onPrevious: (() -> Unit)?,
    val onNext: (() -> Unit)?,
)

/** The design's picture overlays: a soft highlight and a top-dark / bottom-dark gradient under the chrome. */
@Composable
private fun ChromeScrim(landscape: Boolean) {
    Box(
        Modifier.fillMaxSize().background(
            if (landscape) Brush.verticalGradient(0f to Color(0xBD000000), 0.30f to Color(0x29000000), 0.52f to Color(0x33000000), 1f to Color(0xE6000000))
            else Brush.verticalGradient(0f to Color(0x80000000), 0.40f to Color.Transparent, 1f to Color(0xD1000000)),
        ),
    )
}

/**
 * Portrait chrome (design "Player · portrait"): back (22dp) top-left and
 * fullscreen (18dp) top-right in 44dp cells, seek/play/seek in the middle
 * (44 · 48 circle · 44), and the 3dp bar with the two clocks at 600 11px
 * along the bottom with 14dp side padding.
 */
@Composable
private fun BoxScope.PortraitChrome(state: PlaybackState, visible: Boolean, scrubPreviewMs: Long?, scrubFrame: android.graphics.Bitmap?, cb: ChromeCallbacks) {
    val colors = RegolithTheme.colors
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            ChromeScrim(landscape = false)
            IconCell(R.drawable.rg_ic_arrow_down, "Leave the player", 22.dp, cb.onBack, "player_back_button", Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 6.dp))
            IconCell(R.drawable.rg_ic_fullscreen, "Full screen", 18.dp, cb.onFullscreen, "player_fullscreen_button", Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = 6.dp))
            Transport(state, cb, gap = Spacing.s18, circle = 48.dp, glyph = 26.dp, modifier = Modifier.align(Alignment.Center))
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 2.dp)) {
                scrubPreviewMs?.let { ms -> ScrubPreview(ms, scrubFrame, if (state.durationMs > 0) (ms.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                    Text(formatClock(scrubPreviewMs ?: state.positionMs), style = TextStyles.eyebrow.copy(letterSpacing = 0.sp), color = colors.ink, modifier = Modifier.testTag("player_position"))
                    Scrubber(
                        positionMs = state.positionMs, durationMs = state.durationMs, bufferedMs = state.bufferedMs,
                        loop = state.loop, pendingAMs = state.loopPendingAMs, chaptersMs = state.chapterTicks,
                        onScrubStart = cb.onScrubStart, onScrub = cb.onScrub, onScrubEnd = cb.onScrubEnd,
                        trackHeight = 3.dp, showKnob = false, modifier = Modifier.weight(1f),
                    )
                    Text(formatClock(state.durationMs), style = TextStyles.eyebrow.copy(letterSpacing = 0.sp), color = colors.body, modifier = Modifier.testTag("player_duration"))
                }
            }
        }
    }
}

/**
 * Full-screen chrome (design "Player · landscape"): 18/30/12 padding; back
 * with the title (Michroma 16) and meta line top-left; speed, A–B, HW
 * pills and the playback-sheet glyph top-right; 52 · 74 circle · 52 in
 * the middle at 40dp gaps; the clocks at 600 13px around the 4dp track
 * with the red knob.
 *
 * Also the chrome for portrait full screen, where [canCollapse] adds the
 * glyph that puts the picture back in its strip. In landscape there is no
 * such glyph: rotation put you here and rotation takes you back.
 */
@Composable
private fun BoxScope.FullChrome(
    state: PlaybackState,
    visible: Boolean,
    scrubPreviewMs: Long?,
    scrubFrame: android.graphics.Bitmap?,
    cb: ChromeCallbacks,
    canCollapse: Boolean,
) {
    val colors = RegolithTheme.colors
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            ChromeScrim(landscape = true)
            Column(Modifier.fillMaxSize().padding(start = 30.dp, end = 30.dp, top = 18.dp, bottom = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                        IconCell(R.drawable.rg_ic_back, "Back", 20.dp, cb.onBack, "player_back_button")
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s4)) {
                            DisplayText(state.title, style = TextStyles.screenTitle.copy(fontSize = 16.designSp(), lineHeight = 20.8.designSp()), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val meta = listOf(state.sourceLabel) + (state.video?.chips ?: emptyList())
                            Text(meta.filter { it.isNotEmpty() }.joinToString(" · "), style = TextStyles.meta12, color = colors.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                        PillRow(state, cb.onOpenPlayback, cb.onLoopTap, cb.onLoopClear, onMedia = true)
                        IconCell(R.drawable.rg_ic_sliders, "Playback", 19.dp, cb.onOpenPlayback, "player_playback_button", size = 40.dp)
                    }
                }
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                    IconCell(R.drawable.rg_ic_seek_back, "Back 10 seconds", 27.dp, { cb.onSeekBy(-10_000) }, "player_seek_back_button", size = 52.dp)
                    PlayCircle(state, 74.dp, 26.dp, cb.onTogglePlay)
                    IconCell(R.drawable.rg_ic_seek_forward, "Forward 10 seconds", 27.dp, { cb.onSeekBy(10_000) }, "player_seek_forward_button", size = 52.dp)
                }
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                    scrubPreviewMs?.let { ms -> ScrubPreview(ms, scrubFrame, if (state.durationMs > 0) (ms.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                        Text(formatClock(scrubPreviewMs ?: state.positionMs), style = TextStyles.buttonSmall, color = colors.ink, modifier = Modifier.testTag("player_position"))
                        Scrubber(
                            positionMs = state.positionMs, durationMs = state.durationMs, bufferedMs = state.bufferedMs,
                            loop = state.loop, pendingAMs = state.loopPendingAMs, chaptersMs = state.chapterTicks,
                            onScrubStart = cb.onScrubStart, onScrub = cb.onScrub, onScrubEnd = cb.onScrubEnd,
                            trackHeight = 4.dp, showKnob = true, modifier = Modifier.weight(1f),
                        )
                        Text(formatClock(state.durationMs), style = TextStyles.buttonSmall, color = colors.body, modifier = Modifier.testTag("player_duration"))
                        if (canCollapse) {
                            IconCell(R.drawable.rg_ic_fullscreen_exit, "Leave full screen", 18.dp, cb.onFullscreen, "player_fullscreen_button", size = 40.dp)
                        }
                    }
                }
            }
        }
    }
}

/** A glyph in a hit cell, white on the picture. */
/**
 * Previous · back 10 · play · forward 10 · next, at whatever size the layout
 * asks for. One definition, because three chromes drew the middle three and
 * would have drifted apart the moment two more were added to each.
 *
 * The skip keys are always drawn and greyed when there is nowhere to go: a
 * transport row that changes width as you walk through a folder moves the
 * play button under your thumb.
 */
@Composable
private fun Transport(
    state: PlaybackState,
    cb: ChromeCallbacks,
    gap: androidx.compose.ui.unit.Dp,
    circle: androidx.compose.ui.unit.Dp,
    glyph: androidx.compose.ui.unit.Dp,
    cell: androidx.compose.ui.unit.Dp = 44.dp,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
        IconCell(R.drawable.rg_ic_skip_previous, "Previous", glyph - 5.dp, cb.onPrevious ?: {}, "player_previous_button", size = cell, enabled = cb.onPrevious != null)
        IconCell(R.drawable.rg_ic_seek_back, "Back 10 seconds", glyph, { cb.onSeekBy(-10_000) }, "player_seek_back_button", size = cell)
        PlayCircle(state, circle, circle * 0.42f, cb.onTogglePlay)
        IconCell(R.drawable.rg_ic_seek_forward, "Forward 10 seconds", glyph, { cb.onSeekBy(10_000) }, "player_seek_forward_button", size = cell)
        IconCell(R.drawable.rg_ic_skip_next, "Next", glyph - 5.dp, cb.onNext ?: {}, "player_next_button", size = cell, enabled = cb.onNext != null)
    }
}

@Composable
private fun IconCell(icon: Int, description: String, iconSize: androidx.compose.ui.unit.Dp, onClick: () -> Unit, testTag: String, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 44.dp, enabled: Boolean = true) {
    Box(
        modifier.size(size).clickable(interactionSource = null, indication = null, enabled = enabled, onClick = onClick).testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = description,
            tint = if (enabled) RegolithTheme.colors.ink else RegolithTheme.colors.disabledInk,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** The play / pause circle: 42% black with a 34% white hairline (design "On media"). */
@Composable
private fun PlayCircle(state: PlaybackState, size: androidx.compose.ui.unit.Dp, iconSize: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val colors = RegolithTheme.colors
    val showPause = state.playWhenReady && !state.ended
    Box(
        Modifier.size(size).clip(PillShape).background(colors.onMediaCircleBg).border(1.dp, colors.onMediaCircleBorder, PillShape)
            .clickable(interactionSource = null, indication = null, onClick = onClick).testTag("player_play_button"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(if (showPause) R.drawable.rg_ic_pause else R.drawable.rg_ic_play),
            contentDescription = if (showPause) "Pause" else "Play", tint = colors.ink, modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun PillRow(
    state: PlaybackState,
    onOpenPlayback: () -> Unit,
    onLoopTap: () -> Unit,
    onLoopClear: () -> Unit,
    onMedia: Boolean,
    /** False where the speed and decoder panel is already on screen (the wide player). */
    settingsPills: Boolean = true,
    onChapters: () -> Unit = {},
) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s8), verticalAlignment = Alignment.CenterVertically) {
        if (settingsPills) PillButton(text = formatSpeed(state.speed), onClick = onOpenPlayback, onMedia = onMedia, testTag = "player_speed_pill")
        if (onMedia || !settingsPills || state.loop != null || state.loopPendingAMs != null) {
            PillButton(
                text = if (state.loopPendingAMs != null && state.loop == null) "A ·" else "A–B",
                selected = state.loop != null, onClick = onLoopTap, onLongClick = onLoopClear, onMedia = onMedia,
                icon = R.drawable.rg_ic_loop, testTag = "player_loop_pill",
            )
        }
        if (settingsPills) PillButton(text = if (state.hardwareDecoding) "HW" else "SW", onClick = onOpenPlayback, onMedia = onMedia, icon = R.drawable.rg_ic_decoder, testTag = "player_decoder_pill")
        // The pill is drawn as soon as the film is loaded, but it spins until
        // the list has SETTLED — the container has been read and the runtime
        // is known. Opening earlier meant a sheet that resized itself as the
        // parts were recounted underneath it.
        if (!onMedia) {
            PillButton(
                text = "Chapters",
                onClick = { if (state.chaptersReady) onChapters() },
                onMedia = false,
                icon = R.drawable.rg_ic_chapters,
                testTag = "player_chapters_pill",
                loading = !state.chaptersReady,
            )
        }
    }
}

/** "LOOPING A–B": a 28dp red pill with the loop glyph, top-left of the picture while armed. */
@Composable
private fun LoopingPill(modifier: Modifier = Modifier) {
    Row(
        modifier.height(28.dp).background(RegolithTheme.colors.accent, PillShape).padding(horizontal = 10.dp).testTag("player_looping_pill"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(painterResource(R.drawable.rg_ic_loop), contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
        Text("LOOPING A–B", style = TextStyles.buttonSmall.copy(fontSize = 11.designSp(), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = Color.White)
    }
}

/**
 * Under the picture in portrait (design "Player · portrait"): title in
 * Michroma 17, the meta line, the three 40dp frosted pills, and NEXT IN
 * THIS FOLDER as 56dp rows with an 84x47 thumb at 10dp corners.
 *
 * On a wide window (a foldable's inner display) the same material is two
 * columns: what the film is and how it plays on the left, what plays next
 * on the right. The playback settings come inline through [settings]
 * instead of a sheet, because there is room for them.
 */
@Composable
private fun PortraitDetails(
    modifier: Modifier,
    state: PlaybackState,
    onOpenPlayback: () -> Unit,
    onLoopTap: () -> Unit,
    onLoopClear: () -> Unit,
    onNudgeA: (Long) -> Unit,
    onNudgeB: (Long) -> Unit,
    onPlayNext: (Long) -> Unit,
    onChapters: () -> Unit,
    settings: @Composable () -> Unit,
) {
    val wide = LocalWindowShape.current.wide
    val loop = state.loop
    // With a loop set, the panel below the picture IS the loop (design frame 29):
    // span, points, clear. The folder still follows it — the loop is about this
    // film, not about what comes after it.
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = Spacing.s18, end = Spacing.s18, top = Spacing.s18)
            .then(if (loop != null) Modifier.testTag("player_loop_panel") else if (wide) Modifier.testTag("player_details") else Modifier),
        verticalArrangement = Arrangement.spacedBy(if (wide) Spacing.s18 else Spacing.s12),
    ) {
        if (loop != null) {
            AbLoopSheetContent(loop = loop, positionMs = state.positionMs, durationMs = state.durationMs, onNudgeA = onNudgeA, onNudgeB = onNudgeB, onClear = onLoopClear)
        } else {
            TitleBlock(state)
            // A wide window shows the speed and decoder panel further down, so
            // the pills that only open it would be a second control for one
            // setting. A–B and Chapters stay: neither has another entry.
            PillRow(state, onOpenPlayback, onLoopTap, onLoopClear, onMedia = false, settingsPills = !wide, onChapters = onChapters)
        }
        NextInFolder(state, onPlayNext)
        // One column, in the order you use it: the film, then what follows it,
        // then the controls for the film. Two columns were tried first and put
        // the folder level with the settings, which made the settings look like
        // the reason the pane existed.
        if (wide && loop == null) settings()
        Spacer(Modifier.height(Spacing.s30))
    }
}

/** The title in Michroma 17 over its metadata line. Both layouts open with it. */
@Composable
private fun TitleBlock(state: PlaybackState) {
    val colors = RegolithTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
        DisplayText(state.title, style = TextStyles.screenTitle.copy(fontSize = 17.designSp(), lineHeight = 22.1.designSp()), maxLines = 2, overflow = TextOverflow.Ellipsis)
        val meta = (state.video?.chips ?: emptyList()) + listOfNotNull(
            state.durationMs.takeIf { it > 0 }?.let { formatDurationShort(it) },
            state.fileSizeBytes.takeIf { it > 0 }?.let { formatBytes(it) },
        )
        Text(meta.joinToString(" · "), style = TextStyles.meta12, color = colors.metadata, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The Up next card (F6): the file that is about to play, a ring counting
 * [AUTOPLAY_SECONDS] down, and the two ways out. It rides over the ended
 * frame in every layout — portrait, full screen and flex — because by then
 * the picture is finished and there is nothing underneath to keep clear.
 *
 * Cancel does not turn the setting off; it declines this one file. The
 * switch lives in Settings › Playback and in the playback sheet.
 */
@Composable
private fun UpNextCard(item: NextItem, seconds: Int, onPlayNow: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val colors = RegolithTheme.colors
    Column(
        modifier = modifier
            .widthIn(max = 460.dp)
            .clip(CardShape)
            .background(colors.overArt)
            .border(1.dp, colors.frostBorder, CardShape)
            .padding(Spacing.s12)
            .testTag("player_up_next_card"),
        verticalArrangement = Arrangement.spacedBy(Spacing.s12),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s12)) {
            Box(Modifier.size(84.dp, 47.dp).clip(RoundedCornerShape(10.dp))) {
                ArtworkImage(ArtworkRequest(ArtworkOwner.File(item.fileId), ArtworkKind.THUMB), Modifier.fillMaxSize(), fallbackLabel = item.name)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
                Eyebrow("Up next", muted = true)
                Text(item.name, style = TextStyles.rowLabelMedium, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(item.durationMs?.let { formatDurationShort(it) }, formatBytes(item.sizeBytes)).joinToString(" · "),
                    style = TextStyles.meta.copy(lineHeight = 11.designSp()), color = colors.metadata, maxLines = 1,
                )
            }
            CountdownRing(seconds)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
            PrimaryButton(text = "Play now", onClick = onPlayNow, compact = true, testTag = "player_up_next_play", modifier = Modifier.weight(1f))
            SecondaryButton(text = "Cancel", onClick = onCancel, compact = true, testTag = "player_up_next_cancel", modifier = Modifier.weight(1f))
        }
    }
}

/** The seconds left, as a number inside an arc that empties as they go. */
@Composable
private fun CountdownRing(seconds: Int) {
    val colors = RegolithTheme.colors
    val fraction = seconds.toFloat() / AUTOPLAY_SECONDS
    Box(Modifier.size(40.dp).testTag("player_up_next_countdown"), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 3.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = colors.raised, startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize, style = Stroke(width = stroke),
            )
            drawArc(
                color = colors.accent, startAngle = -90f, sweepAngle = 360f * fraction, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize, style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Text("$seconds", style = TextStyles.meta12, color = colors.ink)
    }
}

/**
 * "Next in this folder": 56dp rows with an 84x47 thumb.
 *
 * [emptyState] is for the layout that keeps a column for this whether or not
 * there is anything in it — the last episode of a season still gets the
 * two-column player, and a column that goes blank reads as a screen that
 * failed to draw. Under the picture, where the list is just one more block,
 * nothing is the right amount to draw.
 */
@Composable
private fun NextInFolder(state: PlaybackState, onPlayNext: (Long) -> Unit, emptyState: Boolean = false) {
    val colors = RegolithTheme.colors
    if (state.next.isEmpty()) {
        if (emptyState) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                Eyebrow("Next in this folder", muted = true)
                Text(
                    "Nothing after this one.",
                    style = TextStyles.meta12, color = colors.metadata,
                    modifier = Modifier.testTag("player_next_empty"),
                )
            }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
        Eyebrow("Next in this folder", muted = true)
        state.next.take(10).forEach { item ->
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp)
                    .clickable(interactionSource = null, indication = null) { onPlayNext(item.fileId) }
                    .testTag("player_next_${item.fileId}"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s12),
            ) {
                Box(Modifier.size(84.dp, 47.dp).clip(RoundedCornerShape(10.dp))) {
                    ArtworkImage(ArtworkRequest(ArtworkOwner.File(item.fileId), ArtworkKind.THUMB), Modifier.fillMaxSize(), fallbackLabel = item.name)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
                    Text(item.name, style = TextStyles.rowLabelMedium, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(item.durationMs?.let { formatDurationShort(it) }, formatBytes(item.sizeBytes)).joinToString(" · "),
                        style = TextStyles.meta.copy(lineHeight = 11.designSp()), color = colors.metadata, maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * The frame under the finger while scrubbing, riding above the playhead
 * and clamped to the track, with the time in a pill beneath. The slot
 * keeps its height either way so the track does not jump.
 */
@Composable
private fun ScrubPreview(ms: Long, frame: android.graphics.Bitmap?, fraction: Float) {
    val colors = RegolithTheme.colors
    val previewW = 160.dp
    val previewH = 90.dp
    BoxWithConstraints(Modifier.fillMaxWidth().height(previewH + 28.dp)) {
        val width = if (frame != null) previewW else 80.dp
        val left = (maxWidth * fraction - width / 2).coerceIn(0.dp, (maxWidth - width).coerceAtLeast(0.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.align(Alignment.BottomStart).offset(x = left).width(width)) {
            if (frame != null) {
                Image(
                    bitmap = frame.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(previewW, previewH).clip(CardShape).border(1.dp, colors.onMediaCircleBorder, CardShape).testTag("player_scrub_preview"),
                )
                Spacer(Modifier.height(Spacing.s4))
            }
            Text(formatClock(ms), style = TextStyles.chipOverArt, color = colors.ink, modifier = Modifier.background(colors.overArt, PillShape).padding(horizontal = Spacing.s8, vertical = Spacing.s2).testTag("player_scrub_time"))
        }
    }
}

/** A frosted label over the picture: 700 15px white, white 14% fill, 28% hairline, 8/18 padding. */
@Composable
private fun OnMediaLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = TextStyles.buttonPrimary, color = Color.White,
        modifier = modifier.background(Color(0x24FFFFFF), PillShape).border(1.dp, Color(0x47FFFFFF), PillShape).padding(horizontal = Spacing.s18, vertical = Spacing.s8),
    )
}

/**
 * Brightness / volume mid-drag (design "Player · brightness drag"): the
 * picture under a 34% scrim, an 8×210 rail at 18% white filling to the
 * value, the glyph beneath it, and the value in words 78dp from the top.
 */
@Composable
private fun BoxScope.DragRail(drag: DragOverlay) {
    val colors = RegolithTheme.colors
    val muted = drag.kind == DragKind.Volume && drag.fraction <= 0.001f
    val label = when {
        muted -> "Muted"
        drag.kind == DragKind.Brightness -> "Brightness ${(drag.fraction * 100).roundToInt()}%"
        else -> "Volume ${(drag.fraction * 100).roundToInt()}%"
    }
    Box(Modifier.fillMaxSize().background(Color(0x57000000)))
    OnMediaLabel(label, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 78.dp).testTag("player_drag_pill"))
    val icon = when {
        muted -> R.drawable.rg_ic_volume
        drag.kind == DragKind.Volume -> R.drawable.rg_ic_volume
        else -> R.drawable.rg_ic_brightness
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s12),
        modifier = Modifier.align(if (drag.kind == DragKind.Brightness) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 26.dp),
    ) {
        Box(Modifier.width(8.dp).height(210.dp).clip(PillShape).background(Color(0x2EFFFFFF))) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(drag.fraction.coerceIn(0f, 1f)).background(colors.ink))
        }
        Icon(painterResource(icon), contentDescription = null, tint = colors.ink, modifier = Modifier.size(20.dp))
    }
}

/** "+20s" / "−10s" over the half that was tapped. */
@Composable
private fun BoxScope.SeekPill(label: String, left: Boolean) {
    OnMediaLabel(label, Modifier.align(if (left) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = Spacing.s56).testTag("player_seek_pill"))
}

/**
 * The gesture map (design "Player · gesture map"), shown once the first
 * time the player opens: three zones with a 56dp glyph circle, the
 * gesture at 700 15, what it does at 13/18 and the drag hint at 12/17,
 * a red GESTURES tag top-left and the two footers. Tap anywhere to dismiss.
 */
@Composable
private fun GestureMap(onDismiss: () -> Unit) {
    val colors = RegolithTheme.colors
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(interactionSource = null, indication = null, onClick = onDismiss).testTag("player_gesture_map")) {
        // Weighted from SIDE_ZONE itself, so the map is drawn to the same
        // proportions the gestures actually use. The edge columns are narrow
        // on purpose and their copy is cut to fit rather than wrapped to
        // death.
        Row(Modifier.fillMaxSize()) {
            GestureZone(R.drawable.rg_ic_seek_back, "Double-tap", "Back 10s, stacking.", "Drag for brightness", Modifier.weight(SIDE_ZONE), compact = true)
            GestureZone(R.drawable.rg_ic_pause, "Double-tap", "Play or pause. A single tap shows the controls.", "Drag up for full screen, down to leave it · long-press for 2×", Modifier.weight(1f - SIDE_ZONE * 2))
            GestureZone(R.drawable.rg_ic_seek_forward, "Double-tap", "Forward 10s, stacking.", "Drag for volume", Modifier.weight(SIDE_ZONE), compact = true)
        }
        Row(Modifier.align(Alignment.TopStart).padding(start = 26.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
            Text("GESTURES", style = TextStyles.tag.copy(fontSize = 10.designSp(), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, lineHeight = 13.designSp()), color = Color.White, modifier = Modifier.background(colors.accent, PillShape).padding(horizontal = Spacing.s12, vertical = Spacing.s4))
            Text("Zones are invisible in use — shown here only", style = TextStyles.meta12, color = colors.body)
        }
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 26.dp, vertical = 18.dp)) {
            Text("Swipe down the middle · leave the player", style = TextStyles.settingMeta.copy(lineHeight = 17.designSp()), color = colors.body, modifier = Modifier.weight(1f))
            Text("Drag the scrub bar · thumbnails follow the finger", style = TextStyles.settingMeta.copy(lineHeight = 17.designSp()), color = colors.body, textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun GestureZone(icon: Int, gesture: String, does: String, hint: String, modifier: Modifier, compact: Boolean = false) {
    val colors = RegolithTheme.colors
    Column(modifier.fillMaxHeight().padding(if (compact) Spacing.s8 else Spacing.s18), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.s12, Alignment.CenterVertically)) {
        Box(Modifier.size(56.dp).clip(PillShape).background(colors.onMediaCircleBg).border(1.dp, colors.onMediaCircleBorder, PillShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = colors.ink, modifier = Modifier.size(26.dp))
        }
        Text(gesture, style = TextStyles.buttonPrimary.copy(lineHeight = 18.designSp()), color = colors.ink, textAlign = TextAlign.Center)
        Text(does, style = TextStyles.body.copy(fontSize = 13.designSp(), lineHeight = 18.designSp()), color = colors.body, textAlign = TextAlign.Center)
        Text(hint, style = TextStyles.settingMeta.copy(lineHeight = 17.designSp()), color = colors.metadata, textAlign = TextAlign.Center)
    }
}

/**
 * The chrome over the picture in flex mode: the header, and only the
 * header. Back, the title with its meta line, the speed / A–B / decoder
 * pills and the playback glyph — the same row [FullChrome] draws. The
 * timeline and the transport are in the deck below the hinge, where the
 * hands are when the device is standing on a table.
 */
@Composable
private fun BoxScope.FlexChrome(state: PlaybackState, visible: Boolean, cb: ChromeCallbacks) {
    val colors = RegolithTheme.colors
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            ChromeScrim(landscape = true)
            Row(
                Modifier.fillMaxWidth().padding(start = Spacing.s18, end = Spacing.s18, top = 14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                    IconCell(R.drawable.rg_ic_back, "Back", 20.dp, cb.onBack, "player_back_button")
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s4)) {
                        DisplayText(state.title, style = TextStyles.screenTitle.copy(fontSize = 16.designSp(), lineHeight = 20.8.designSp()), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val meta = listOf(state.sourceLabel) + (state.video?.chips ?: emptyList())
                        Text(meta.filter { it.isNotEmpty() }.joinToString(" · "), style = TextStyles.meta12, color = colors.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                    PillRow(state, cb.onOpenPlayback, cb.onLoopTap, cb.onLoopClear, onMedia = true)
                    IconCell(R.drawable.rg_ic_sliders, "Playback", 19.dp, cb.onOpenPlayback, "player_playback_button", size = 40.dp)
                }
            }
        }
    }
}

/**
 * Everything below the hinge in flex mode. The filmstrip is the scrub
 * preview turned inside out: instead of one frame under the finger while
 * dragging, the whole film is laid out at once and a tap goes there. Then
 * the timeline, the transport at the size a standing device wants, and what
 * plays next.
 *
 * Unlike the chrome over the picture the deck never hides: it is not on top
 * of anything.
 */
@Composable
private fun FlexDeck(
    state: PlaybackState,
    strip: List<StripFrame>,
    scrubPreviewMs: Long?,
    cb: ChromeCallbacks,
    onPlayNext: (fileId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RegolithTheme.colors
    // While a drag is live the strip follows the finger, which is what
    // replaces the floating preview the phone shows.
    val here = scrubPreviewMs ?: state.positionMs
    val currentMs = strip.minByOrNull { (it.positionMs - here).absoluteValue }?.positionMs
    Column(
        modifier.padding(horizontal = Spacing.s30).padding(top = Spacing.s18, bottom = Spacing.s12).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(Spacing.s18),
    ) {
        if (strip.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().testTag("player_strip"), horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                strip.forEach { frame ->
                    StripCell(
                        frame = frame,
                        current = frame.positionMs == currentMs,
                        onSeek = { if (state.durationMs > 0) cb.onScrubEnd(frame.positionMs.toFloat() / state.durationMs) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s12)) {
            Text(formatClock(here), style = TextStyles.buttonSmall, color = colors.ink, modifier = Modifier.testTag("player_position"))
            Scrubber(
                positionMs = state.positionMs, durationMs = state.durationMs, bufferedMs = state.bufferedMs,
                loop = state.loop, pendingAMs = state.loopPendingAMs, chaptersMs = state.chapterTicks,
                onScrubStart = cb.onScrubStart, onScrub = cb.onScrub, onScrubEnd = cb.onScrubEnd,
                trackHeight = 4.dp, showKnob = true, modifier = Modifier.weight(1f),
            )
            Text(formatClock(state.durationMs), style = TextStyles.buttonSmall, color = colors.body, modifier = Modifier.testTag("player_duration"))
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Transport(state, cb, gap = Spacing.s30, circle = 74.dp, glyph = 27.dp, cell = 52.dp)
        }
        state.next.firstOrNull()?.let { item ->
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp)
                    .clickable(interactionSource = null, indication = null) { onPlayNext(item.fileId) }
                    .testTag("player_next_${item.fileId}"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s12),
            ) {
                Box(Modifier.size(84.dp, 47.dp).clip(RoundedCornerShape(10.dp))) {
                    ArtworkImage(ArtworkRequest(ArtworkOwner.File(item.fileId), ArtworkKind.THUMB), Modifier.fillMaxSize(), fallbackLabel = item.name)
                }
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
                    Eyebrow("Up next", muted = true)
                    Text(item.name, style = TextStyles.rowLabelMedium, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** One frame of the filmstrip: the picture once it lands, its time beneath, ringed when the playhead is in its slice. */
@Composable
private fun StripCell(frame: StripFrame, current: Boolean, onSeek: () -> Unit, modifier: Modifier = Modifier) {
    val colors = RegolithTheme.colors
    Column(
        modifier.clickable(interactionSource = null, indication = null, onClick = onSeek).testTag("player_strip_${frame.positionMs}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s4),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .then(if (current) Modifier.border(2.dp, colors.ink, ThumbShape) else Modifier)
                .padding(if (current) 3.dp else 0.dp)
                .clip(ThumbShape)
                .background(colors.skeleton),
        ) {
            frame.bitmap?.let { bmp ->
                Image(bitmap = bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Text(formatClock(frame.positionMs), style = TextStyles.meta.copy(lineHeight = 11.designSp()), color = if (current) colors.ink else colors.metadata, maxLines = 1)
    }
}

/**
 * The ambient light behind a letterboxed picture: the title's own poster,
 * blurred past recognition, scaled out so the blur has no edge, and dimmed
 * under a scrim. A 2:39 film then sits in its own colour instead of a black
 * band, which is the whole point.
 *
 * The poster is the one the artwork pipeline already generated and cached
 * for this file, so the glow costs no read over the share and works with
 * the network gone. Nothing is drawn until it resolves: an unmatched file
 * keeps the black it has now rather than gaining a blurred placeholder.
 */
@Composable
private fun AmbientGlow(fileId: Long?, modifier: Modifier = Modifier, spill: Boolean = false) {
    if (fileId == null) return
    // The backdrop, not the poster: this sits behind a 16:9 picture and
    // fills the bands beside it, so a 2:3 centre crop would show the
    // middle strip of the frame stretched across the whole window.
    val request = remember(fileId) { ArtworkRequest(ArtworkOwner.File(fileId), ArtworkKind.BACKDROP) }
    Box(modifier.clipToBounds()) {
        SubcomposeAsyncImage(
            model = request,
            contentDescription = null,
            loading = {},
            error = {},
            success = { SubcomposeAsyncImageContent(contentScale = ContentScale.Crop) },
            modifier = Modifier
                .fillMaxSize()
                .scale(GLOW_SCALE)
                .blur(GLOW_BLUR, BlurredEdgeTreatment.Unbounded)
                .alpha(GLOW_ALPHA)
                .testTag("player_ambient_glow"),
        )
        // Two jobs, two scrims. Behind the windowed player the glow has to
        // fall off downward the way spill light does — strongest around the
        // picture, back to the app's own ground by the bottom of the screen.
        // Filling the letterbox bars it has to be even, because the bars are
        // above AND below the picture and a gradient would make the top one a
        // different colour from the bottom one.
        Box(
            Modifier.fillMaxSize().background(
                if (spill) Brush.verticalGradient(0f to SPILL_SCRIM, 1f to SPILL_SCRIM)
                else Brush.verticalGradient(0f to Color(0x4D000000), 0.62f to Color(0xA6000000), 1f to RegolithTheme.colors.ground),
            ),
        )
    }
}

/** Enough blur that no shape survives; the glow is colour, not a picture. */
private val GLOW_BLUR = 56.dp

/** Scaled past the edges so the blur has nothing to fade into. */
private const val GLOW_SCALE = 1.35f

private const val GLOW_ALPHA = 0.65f

/**
 * How far a middle drag must travel, as a fraction of the picture's height,
 * before it enters or leaves full screen. A flick commits at any distance;
 * this is the floor for a slow, deliberate drag, and it is what stops a
 * stray finger from flipping the layout.
 */
private const val FULLSCREEN_DRAG_FRACTION = 0.12f

/**
 * How long the Up next card waits before it plays on by itself. Long enough
 * to read the title and cancel, short enough that it does not read as a
 * stall on a film that has plainly finished.
 */
private const val AUTOPLAY_SECONDS = 10

/**
 * The landscape two-pane player's share of the window for its right column,
 * clamped to 300–460dp. A fraction rather than a fixed width so a tablet
 * gives the picture more room than an unfolded phone does; a clamp because
 * a list of 84×47 thumbs and a filename stops improving past ~460dp.
 */
private const val SIDE_COLUMN_FRACTION = 0.34f

/**
 * How far the ambient bars are knocked back from the poster they are made
 * of. Dark enough that the eye reads them as spill from the picture rather
 * than as a second, blurrier picture competing with it.
 */
private val SPILL_SCRIM = Color(0xB8000000)
