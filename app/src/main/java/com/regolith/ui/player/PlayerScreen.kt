package com.regolith.ui.player

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.DirectNavigationEventInput
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.collectAsState
import androidx.compose.animation.core.Easing
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.currentStateAsState
import com.regolith.ui.navigation.PLAYER_MOTION_MS
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.EnterExitState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.util.lerp
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import com.regolith.ui.components.FlightEasing
import kotlinx.coroutines.flow.first
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.rememberPresentationState
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.regolith.R
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.domain.playback.AmbientLight
import com.regolith.domain.playback.SeekStacker
import com.regolith.domain.transfer.TransferStatus
import com.regolith.player.PlaybackState
import com.regolith.ui.components.StrataLoader
import com.regolith.ui.components.OnMediaLabel
import com.regolith.ui.titledetail.TransferView
import com.composables.icons.lucide.R as LucideR
import com.regolith.domain.playback.ChapterDraft
import com.regolith.domain.playback.ChapterSource
import com.regolith.domain.playback.ChapterSyncState
import com.regolith.domain.playback.PlayerOrientation
import com.regolith.domain.playback.RepeatMode
import com.regolith.player.NextItem
import com.regolith.ui.components.ArtworkImage
import com.regolith.ui.components.rememberFlightLanding
import com.regolith.ui.components.Filmstrip
import com.regolith.ui.components.StripFrame
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.ErrorCard
import com.regolith.ui.components.PrimaryButton
import com.regolith.ui.components.SecondaryButton
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.ConfirmDialog
import com.regolith.ui.components.MessageKind
import com.regolith.ui.components.RegolithSnackbarHost
import com.regolith.ui.components.showMessage
import androidx.compose.material3.SnackbarHostState
import com.regolith.ui.components.PillButton
import com.regolith.ui.components.Scrubber
import com.regolith.ui.theme.CardShape
import com.regolith.ui.theme.PillShape
import com.regolith.ui.adaptive.FoldPosture
import com.regolith.ui.adaptive.LocalWindowShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.components.ArtworkLight
import androidx.compose.ui.draw.clipToBounds
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
    /** Open the poster editor on (file, position). */
    onMakePoster: (Long, Long) -> Unit,
    modifier: Modifier = Modifier,
    /** Opened from the mini player: the picture grows out of it rather than the screen sliding in. */
    expandFromMini: Boolean = false,
    /** Opened by a tap on the film's picture, which flies into this one's ([RegolithKey.Player.flies]). */
    fliesIn: Boolean = false,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val player by viewModel.player.collectAsStateWithLifecycle()
    // A Moments reel instead of one film (Reel.kt): its moments take the
    // timeline's place, and Previous and Next step between them.
    val reel = state.reel
    // The timeline and the clocks run off this rather than off
    // state.positionMs, which is only refreshed four times a second.
    val smooth = rememberSmoothProgress(player)
    val scrubThumbnails by viewModel.scrubThumbnails.collectAsStateWithLifecycle()
    val scrubFrame by viewModel.scrubFrame.collectAsStateWithLifecycle()
    val ambientLight by viewModel.ambientLight.collectAsStateWithLifecycle()
    val ambientSample by rememberAmbientLight(light = ambientLight, key = state.fileId)
    val chapterFrames by viewModel.chapterFrames.collectAsStateWithLifecycle()
    val gesturesSeen by viewModel.gesturesSeen.collectAsStateWithLifecycle()
    val orientation by viewModel.orientation.collectAsStateWithLifecycle()
    val brightness by viewModel.brightness.collectAsStateWithLifecycle()
    val transfer by viewModel.transfer.collectAsStateWithLifecycle()
    val onPhone by viewModel.onPhone.collectAsStateWithLifecycle()
    val draft by viewModel.chapterDraft.collectAsStateWithLifecycle()
    val chaptersOpenable by viewModel.chaptersOpenable.collectAsStateWithLifecycle()
    val chapterSaving by viewModel.chapterSaving.collectAsStateWithLifecycle()
    val nameSuggestions by viewModel.chapterNameSuggestions.collectAsStateWithLifecycle()
    // "Saved to the share", at the bottom, gone on its own.
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.chapterSaveMessages.collect { snackbar.showMessage(it, MessageKind.DONE) } }
    LaunchedEffect(viewModel) { viewModel.posterMessages.collect { snackbar.showMessage(it, MessageKind.DONE) } }
    val canMakePoster by viewModel.canMakePoster.collectAsStateWithLifecycle()
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
        }
    }

    // Brightness is the ViewModel's, applied here whenever it changes and
    // let go of exactly once, when the player leaves. It used to be reset in
    // the effect above, which re-runs on every layout change — so entering
    // full screen, or turning the phone, quietly undid the drag you had just
    // made. The window is one window; its brightness should not know or care
    // which layout is on it.
    LaunchedEffect(system, brightness) { brightness?.let(system::setBrightness) }
    DisposableEffect(system) { onDispose { system.resetBrightness() } }

    // Wherever full screen was a choice, back un-chooses it before it leaves the player.
    BackHandler(enabled = fullscreen && !forcedFullscreen) { fullscreen = false }
    // The editor is a level of its own: back closes it (asking first if the draft changed).
    var confirmRevert by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    BackHandler(enabled = draft != null) { if (draft?.dirty == true) confirmDiscard = true else viewModel.discardChapterEdit() }

    // Save progress when the app goes to the background mid-playback.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) viewModel.onPause() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // --- Coming and going (MiniPlayer.kt). The screen moves itself, on its own
    // NavDisplay transition. Put away, the picture shrinks into the spot the
    // mini player's picture is about to take while the rest of the player
    // fades off the page underneath; opened from the mini player, the same in
    // reverse. Full screen and the hinge layout, and anything opened another
    // way, slide as a pushed screen always has. The back swipe scrubs the same
    // transition, so the picture follows the thumb.
    val handoff = LocalMiniPlayerHandoff.current
    val screen = LocalNavAnimatedContentScope.current
    // Pinched to fill its box (onZoom) rather than fitted in it; how the picture lands, too.
    var fill by remember { mutableStateOf(false) }
    // The Compose root, which the video surface hangs under (videoFrame).
    val rootView = LocalView.current
    val away by screen.transition.animateFloat(
        transitionSpec = { tween(PLAYER_MOTION_MS, easing = FlightEasing) },
        label = "playerAway",
    ) { s -> if (s == EnterExitState.Visible) 0f else 1f }
    val leaving = screen.transition.targetState == EnterExitState.PostExit
    val slot = handoff?.slot
    // Not a finished film or a failed one: leaving those stops them, so there
    // is no mini player to shrink into (PlayerViewModel.onCleared).
    val shrinks = slot != null && !immersive && !flex && (leaving || expandFromMini) &&
        !state.ended && state.error == null
    var pictureBounds by remember { mutableStateOf<Rect?>(null) }
    // --- The swipe down into the mini player, the way YouTube does it: the
    // finger carries the picture down into the mini player's spot while the
    // player's ground fades off the page underneath, and letting go finishes
    // the same motion from where it is. The swipe drives the very transition
    // Back plays, which the system's back swipe already scrubs, through a
    // back gesture of the app's own. It used to shrink the picture in place
    // and spring back as the put-away began: the jolt.
    val navEvents = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
    val swipeInput = remember { DirectNavigationEventInput() }
    DisposableEffect(navEvents) {
        navEvents?.addInput(swipeInput)
        onDispose { navEvents?.removeInput(swipeInput) }
    }
    val noGesture = remember { MutableStateFlow<NavigationEventTransitionState>(NavigationEventTransitionState.Idle) }
    val backGesture by (navEvents?.transitionState ?: noGesture).collectAsState()
    // Where a swipe down is the put-away: wherever Back would shrink the picture.
    val swipeCanShrink by rememberUpdatedState(
        navEvents != null && handoff?.slot != null && !immersive && !flex && !state.ended && state.error == null,
    )
    // How far a finger's travel down the window has carried the picture: its
    // top keeping pace with the finger on the way to the mini player's.
    val shrinkFraction = { fingerPx: Float ->
        val from = pictureBounds
        val to = handoff?.slot
        val travel = if (from != null && to != null) to.top - from.top else 0f
        if (travel <= 1f) 0f else (fingerPx / travel).coerceIn(0f, 1f)
    }
    // A flick: fast enough that it, not where the picture is, decides.
    val shrinkFlickPxPerS by rememberUpdatedState(with(LocalDensity.current) { SHRINK_FLICK_DP_PER_S.dp.toPx() })
    // While it is on screen the mini player stays away from the picture: one
    // ExoPlayer draws on one surface. Shrinking, it lets go just before it
    // lands, and hands over the frame it is showing with the film.
    DisposableEffect(handoff) {
        handoff?.playerShowing = true
        onDispose {
            handoff?.playerShowing = false
            handoff?.playerPicture = null
        }
    }
    // Only when it shrinks into it. Sliding out, the player lets go as it
    // goes; a finished film slides out, and is stopped as it goes, so a mini
    // player shown before then would only flash up empty-handed.
    LaunchedEffect(handoff, leaving, shrinks) {
        if (!leaving || !shrinks || handoff == null) return@LaunchedEffect
        // Not while a finger still holds the swipe (the app's or the system's):
        // it may let go short of the mini player and bring the picture back,
        // and a picture already handed over would stay black.
        snapshotFlow { away >= MINI_HANDOVER_AT && backGesture is NavigationEventTransitionState.Idle }.first { it }
        // The frame on this surface now, for the mini player to show until its
        // own surface has drawn one, and to hold over itself as it fades in
        // (MiniPlayerHandoff): a new surface is empty for a frame or several,
        // and that was the black box behind the picture as it landed.
        val frame = rootView.videoFrame()
        handoff.frame = frame
        handoff.landed = LandedFrame(frame, if (fill) ContentScale.Crop else ContentScale.Fit)
        handoff.playerShowing = false
    }
    // The picture's way into the slot and out of it: from where it sits on the
    // page to the mini player's picture, by [away]. Both are 16:9, so one scale.
    val intoSlot = Modifier
        .onGloballyPositioned {
            pictureBounds = it.boundsInRoot()
            // Also where the floating window grows out of, if the app is left now.
            handoff?.playerPicture = pictureBounds
        }
        .graphicsLayer {
            val from = pictureBounds
            if (!shrinks || from == null || slot == null || from.width <= 0f) return@graphicsLayer
            transformOrigin = TransformOrigin(0f, 0f)
            val scale = lerp(1f, slot.width / from.width, away)
            scaleX = scale
            scaleY = scale
            translationX = lerp(0f, slot.left - from.left, away)
            translationY = lerp(0f, slot.top - from.top, away)
        }
    // --- Flown into (PosterFlight.kt). Opened by a tap on the film's picture
    // (a Continue watching card, or Play under the title page's hero), that
    // picture flies into this one's, by hand, and the player fades in under
    // it rather than sliding: a slide would carry the landing spot sideways
    // while the picture is in the air. Only upright and not full screen, as
    // the canvas has it, and only on the way in: Back shrinks the picture
    // into the mini player instead.
    val flies = fliesIn && !expandFromMini && !immersive && !flex
    val fadesIn = flies && !leaving
    val stillOwner = state.fileId?.let { ArtworkOwner.File(it) }
    val stillThumb = stillOwner?.let { ArtworkRequest(it, ArtworkKind.THUMB) }
    // The film's still: the backdrop when it is already made, else the thumb
    // (PlayerViewModel.stillFor). The thumb at once, so a new film never
    // shows the last one's picture while its own is looked up.
    var still by remember(state.fileId) { mutableStateOf(stillThumb) }
    LaunchedEffect(state.fileId) { state.fileId?.let { still = viewModel.stillFor(it) } }
    // No picture of its own to fly: the tile's stays in the air all the way,
    // then hands over to the still as it lands.
    val landing = rememberFlightLanding(stillOwner, picture = null, placeholder = null, enabled = flies)
    // The picture's surface waits for the landing. A SurfaceView ignores the
    // fade, so in place during the flight it would be a black box under the
    // picture still in the air.
    val surfaceUp = !flies || (screen.transition.currentState == EnterExitState.Visible && landing.landed)
    // The film was opened held while the picture flew (PlayerViewModel); it
    // starts once there is somewhere to see it.
    LaunchedEffect(surfaceUp) { if (surfaceUp) viewModel.arrived() }
    // The still covers the picture until the film's first frame is drawn on
    // it: what a flying picture lands on, and a picture rather than black
    // while a film opens from the share. Not when grown out of the mini
    // player, whose live picture it would interrupt.
    val presentation = rememberPresentationState(player)
    // A reel's still is its moment's own frame, which the Moments tab has
    // already made: the clip opens on it.
    val reelFrame = reel?.clip?.let { ArtworkRequest(ArtworkOwner.Moment(it.fileId, it.startMs), ArtworkKind.THUMB) }
    val stillShown = (reelFrame ?: still) != null && !expandFromMini && (!surfaceUp || presentation.coverSurface)
    // Grown out of the mini player: the frame it was showing, which it handed
    // over (MiniPlayerHandoff.frame), under this surface until the surface has
    // drawn one of its own. A new surface is empty for a frame or several.
    val handedFrame = handoff?.frame?.takeIf { expandFromMini && presentation.coverSurface }
    LaunchedEffect(presentation.coverSurface) {
        if (expandFromMini && !presentation.coverSurface) handoff?.frame = null
    }
    val stillAlpha by animateFloatAsState(if (stillShown) 1f else 0f, tween(STILL_FADE_MS), label = "playerStill")
    // Between a reel's moments: the next one's name over the picture for a
    // moment and a half (the canvas's question 9), then the picture alone.
    var reelCaption by remember { mutableStateOf(false) }
    LaunchedEffect(reel?.index, reel?.clip) {
        if (reel == null) return@LaunchedEffect
        reelCaption = true
        delay(REEL_CAPTION_MS)
        reelCaption = false
    }

    // Everything but the picture: gone well before the picture lands, so the
    // page underneath is what it lands on; flown into, it fades in.
    val pageAlpha = when {
        shrinks -> (1f - away * 1.6f).coerceIn(0f, 1f)
        fadesIn -> 1f - away
        else -> 1f
    }
    // The slide, for the ways in and out that neither shrink nor fly.
    val slide = Modifier.graphicsLayer { if (!shrinks && !fadesIn) translationX = away * size.width }

    // Who decides what follows when the film ends: this screen's Up next card
    // while you are looking at it, the session (straight on, no card)
    // whenever you are not — put away, floating, or with the screen off.
    val inPip = LocalInPictureInPicture.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val inFront = lifecycleState.isAtLeast(Lifecycle.State.RESUMED) && !inPip
    DisposableEffect(viewModel, inFront) {
        viewModel.ownTheEnd(inFront)
        onDispose { viewModel.ownTheEnd(false) }
    }
    // Back from the floating window, the surface is new: a film that ended in
    // the window has its last frame drawn again rather than a black box.
    LaunchedEffect(inPip) { if (!inPip) viewModel.redrawIfEnded() }

    // --- Up next (F6). Four things have to be true before the app plays on
    // by itself: you left the setting on, the film actually ran to its end
    // (playWhenReady is still set, so scrubbing to the last second while
    // paused is not an ending), the folder has another file, and you have
    // not already said no to this one.
    val autoplayNext by viewModel.autoplayNext.collectAsStateWithLifecycle()
    val autoplayImmediately by viewModel.autoplayImmediately.collectAsStateWithLifecycle()
    val upNext = state.upNext
    var autoplayCancelled by remember(state.fileId) { mutableStateOf(false) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    // An explicit queue beats the setting: tapping Play all or Shuffle on a
    // folder of seven is a request for all seven, and a queue that stopped
    // after the first would be a bug in any other player. "Keep playing"
    // governs what happens when you open ONE file and it ends.
    // Repeating all is a third way of saying "keep going", and a louder one
    // than the setting: it was set on this film, about this folder.
    val autoplayArmed = state.playsOnTo(autoplayNext) != null &&
        !autoplayCancelled && state.ended && state.playWhenReady
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
    // full screen, or into the mini player). `snap` while the finger is down so
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
    // One tick as you cross the point of no return into full screen, so you
    // can feel that letting go now will do something. Not on the way down:
    // the swipe into the mini player follows the thumb with no line to
    // cross, and that is how the owner wants a swipe down to feel.
    val past = dragUp >= 1f
    LaunchedEffect(past) { if (past) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }

    var controlsVisible by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    // A chapter list belongs to one file. Autoplay can change the file while
    // the sheet is open, and a list of the last film's chapters over the next
    // one is worse than no list.
    LaunchedEffect(state.fileId) { if (sheet == Sheet.Chapters) sheet = null }
    // A sync note is read once: it goes when the sheet that showed it closes.
    LaunchedEffect(sheet) { if (sheet != Sheet.Chapters && state.chapterSyncNote != null) viewModel.clearChapterNote() }
    // The prefetch in the ViewModel does this when the chapter list settles,
    // long before the tap. This stays as a safety net for a film whose list
    // changed under an open sheet: `requestAll` skips buckets already in the
    // index, so asking twice costs nothing.
    LaunchedEffect(sheet, state.fileId, state.durationMs) {
        if (sheet == Sheet.Chapters) viewModel.requestChapterFrames()
    }
    var drag by remember { mutableStateOf<DragOverlay?>(null) }
    var seekLabel by remember { mutableStateOf<String?>(null) }
    var scrubPreviewMs by remember { mutableStateOf<Long?>(null) }
    val stacker = remember { SeekStacker() }
    val showGestureMap = gesturesSeen == false

    // Controls auto-hide 3 s after the last interaction while playing, never
    // mid-scrub — and never while the chapter editor is open, because the
    // timeline is where its flags live.
    val editing = draft != null
    LaunchedEffect(controlsVisible, state.playWhenReady, sheet, scrubPreviewMs != null, editing) {
        if (controlsVisible && state.playWhenReady && sheet == null && scrubPreviewMs == null && !editing) {
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
            /** This drag is the put-away itself, scrubbed by the finger (see swipeCanShrink). */
            private var shrinkSwipe = false
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
                    shrinkSwipe = false
                    middleDragging = true
                    middleDrag = 0f
                    return
                }
                val kind = if (zone == Zone.LEFT) DragKind.Brightness else DragKind.Volume
                dragValue = if (kind == DragKind.Brightness) (brightness ?: system.brightness()) else system.volume()
                drag = DragOverlay(kind, dragValue, xFraction)
            }
            override fun onDrag(dyFraction: Float) {
                if (zone == Zone.MIDDLE) {
                    middleDy += dyFraction
                    // Down where Back would shrink the picture: the drag is that
                    // put-away from its first moment, not a preview of it.
                    if (!shrinkSwipe && middleDy > 0f && swipeCanShrink) {
                        shrinkSwipe = true
                        middleDrag = 0f
                        swipeInput.backStarted(shrinkEvent(0f))
                    }
                    if (shrinkSwipe) {
                        // The gesture's progress is the transition's time, so the
                        // transition's own easing is undone first.
                        val carried = shrinkFraction(middleDy * (pictureBounds?.height ?: 0f))
                        swipeInput.backProgressed(shrinkEvent(FlightEasing.timeFor(carried)))
                    } else {
                        middleDrag = middleDy
                    }
                    return
                }
                val d = drag ?: return
                dragValue = (dragValue - dyFraction * 1.5f).coerceIn(0f, 1f)
                if (d.kind == DragKind.Brightness) viewModel.setBrightness(dragValue) else system.setVolume(dragValue)
                drag = d.copy(fraction = dragValue)
            }
            override fun onDragEnd(velocityY: Float) {
                val flingDown = velocityY > FLING_DOWN_PX_PER_S
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
                val carried = shrinkFraction(middleDy * (pictureBounds?.height ?: 0f))
                middleDy = 0f
                if (shrinkSwipe) {
                    // Let go, the way YouTube does it: a flick decides by its
                    // direction, and otherwise where the picture is does, past
                    // halfway on to the mini player and short of it back up.
                    // There is no line to cross before letting go means
                    // anything, so nothing marks one.
                    shrinkSwipe = false
                    val finish = when {
                        velocityY > shrinkFlickPxPerS -> true
                        velocityY < -shrinkFlickPxPerS -> false
                        else -> carried >= SHRINK_SETTLE_FRACTION
                    }
                    if (finish) swipeInput.backCompleted() else swipeInput.backCancelled()
                    return
                }
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
        // into the mini player only when there is no full screen to leave.
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
        onPrevious = if (reel != null) viewModel::reelPrevious else state.upPrevious?.let { p -> { viewModel.playNext(p.fileId) } },
        onNext = if (reel != null) reel.takeIf { it.hasNext }?.let { { viewModel.reelNext() } } else upNext?.let { n -> { viewModel.playNext(n.fileId) } },
        onChapters = { if (draft == null) sheet = Sheet.Chapters },
        onCycleRotation = {
            viewModel.setOrientation(PlayerOrientation.entries[(orientation.ordinal + 1) % PlayerOrientation.entries.size])
            controlsVisible = true
        },
        onKeep = viewModel::keepOnDevice,
        onRemove = viewModel::removeFromDevice,
        canKeep = !onPhone,
        onShuffle = viewModel::toggleShuffle,
        onRepeat = viewModel::cycleRepeat,
        chaptersOpenable = chaptersOpenable,
        onReelClip = { viewModel.reelTo(it); controlsVisible = true },
        onWatchFromHere = viewModel::watchFromHere,
        onReelShuffle = viewModel::toggleReelShuffle,
    )
    // The rotation lock, where locking would do anything (see [rotationLockable]).
    val lockable = rotationLockable()

    // The editor, built once and shown in two places: the details column
    // (portrait, unfolded) or a sheet of its own with a timeline inside it
    // (landscape, half-open), where there is no column to put it in.
    val editor: @Composable (ChapterDraft, Boolean) -> Unit = { d, withScrubber ->
        ChapterEditorContent(
            draft = d, positionMs = state.positionMs, durationMs = state.durationMs, showScrubber = withScrubber, smooth = smooth,
            onMark = viewModel::markChapterAtPlayhead, onSelect = viewModel::selectMark, onMove = viewModel::moveMark,
            onNudge = viewModel::nudgeMark, onRename = viewModel::renameMark, onRemove = viewModel::removeMark,
            onDone = viewModel::saveChapters,
            onCancel = { if (d.dirty) confirmDiscard = true else viewModel.discardChapterEdit() },
            onClearAll = viewModel::clearAllMarks,
            suggestions = nameSuggestions,
            saving = chapterSaving,
            onScrubStart = chromeCallbacks.onScrubStart, onScrub = chromeCallbacks.onScrub, onScrubEnd = chromeCallbacks.onScrubEnd,
        )
    }
    val editorPanel: (@Composable () -> Unit)? = draft?.let { d -> { editor(d, false) } }
    val pillOrientation = orientation.takeIf { lockable }

    val video = @Composable {
        // Never its own ground: whatever the picture does not cover is the
        // layout's to fill, which is how the letterbox bars pick up the glow
        // instead of being black.
        Box(Modifier.fillMaxSize()) {
            // An empty TextureView draws nothing, so this shows through it.
            handedFrame?.let {
                Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = if (fill) ContentScale.Crop else ContentScale.Fit)
            }
            // Floating, the picture-in-picture window has the film (one
            // ExoPlayer draws on one surface); this takes it back after.
            player?.takeUnless { inPip }?.takeIf { surfaceUp }?.let { p ->
                // A SurfaceView goes straight to the compositor and cannot be
                // read back; a TextureView draws through the view hierarchy and
                // can. That is the trade behind the Ambient light setting, and
                // both live lights read the picture, so both need a TextureView.
                // The windowed player needs one whatever the setting: its
                // picture moves (into the mini player, out of it, under a
                // thumb), and a SurfaceView moving on a real display shows its
                // black background in a box around the picture and tears.
                // Full screen, where nothing moves, keeps the setting's choice.
                // No shutter: Media3's is a black box over a new surface until
                // its first frame, which is exactly the box that flashed; what
                // is under the surface shows through it instead.
                ContentFrame(
                    p, Modifier.fillMaxSize(),
                    if (ambientLight.live || !immersive) SURFACE_TYPE_TEXTURE_VIEW else SURFACE_TYPE_SURFACE_VIEW,
                    if (fill) ContentScale.Crop else ContentScale.Fit,
                    shutter = {},
                )
            }
            if ((reelFrame ?: still) != null && stillAlpha > 0f) {
                Box(
                    Modifier.fillMaxSize()
                        .then(landing.modifier)
                        .graphicsLayer { alpha = stillAlpha }
                        .testTag("player_still"),
                ) {
                    ArtworkImage(
                        reelFrame ?: still, Modifier.fillMaxSize(),
                        contentScale = if (fill) ContentScale.Crop else ContentScale.Fit,
                        placeholder = if (reelFrame != null) null else stillThumb,
                    )
                }
            }
            Box(Modifier.fillMaxSize().playerGestures(gestures).testTag("player_gesture_layer"))
            // The chrome over the picture fades in with the rest of the player
            // when a picture flies in, rather than waiting on it at full strength.
            Box(Modifier.fillMaxSize().graphicsLayer { if (fadesIn) alpha = 1f - away }) {
                if (state.isBuffering) {
                    // The mark, not a circle: 48dp is the smallest size the five
                    // bands still read at (docs/ARCHITECTURE.md).
                    StrataLoader(modifier = Modifier.align(Alignment.Center), height = 48.dp, testTag = "player_buffering")
                }
                if (flex) {
                    // Above the fold there is only the header: the timeline and the
                    // transport live in the deck below, where the hands are.
                    FlexChrome(state, controlsVisible && drag == null, chromeCallbacks, transfer, smooth)
                } else if (immersive) {
                    // The collapse glyph only appears when full screen was a
                    // choice; in landscape it is the rotation, so there is
                    // nothing for a button to undo.
                    FullChrome(state, controlsVisible && drag == null, scrubPreviewMs, scrubFrame, chromeCallbacks, canCollapse = !forcedFullscreen, orientation = pillOrientation, transfer = transfer, draft = draft, smooth = smooth)
                } else {
                    PortraitChrome(state, controlsVisible && drag == null, scrubPreviewMs, scrubFrame, chromeCallbacks, draft, smooth)
                }
                if (reel != null) {
                    // Always there, as a film's timeline would be with the controls.
                    // Over, the last bar stays full: the timeline's own reading stops at the end.
                    ReelSegments(
                        reel, { if (state.ended) 1f else smooth.fraction() },
                        Modifier.align(Alignment.TopCenter).padding(start = 10.dp, end = 10.dp, top = 10.dp),
                    )
                    // The moment's name for its first moments, and with the
                    // controls; full screen has its own title for that.
                    AnimatedVisibility(
                        visible = reelCaption || (controlsVisible && drag == null && !immersive),
                        enter = fadeIn(), exit = fadeOut(),
                        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                    ) {
                        // On its own dark foot: white type over a bright frame would vanish.
                        Box(
                            Modifier.fillMaxWidth()
                                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB8000000))))
                                .padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 12.dp),
                        ) {
                            ReelCaption(reel.clip, large = windowShape.wide)
                        }
                    }
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
        Column(modifier.fillMaxSize().then(slide).background(Color.Black).testTag("player_screen")) {
            Box(Modifier.fillMaxWidth().height(topHeight)) {
                AmbientGlow(state.fileId, Modifier.fillMaxSize(), light = ambientLight, sample = ambientSample)
                video()
            }
            FlexDeck(
                state = state,
                strip = strip,
                scrubPreviewMs = scrubPreviewMs,
                cb = chromeCallbacks,
                onPlayNext = viewModel::playNext,
                draft = draft,
                smooth = smooth,
                modifier = Modifier.fillMaxWidth().weight(1f).background(RegolithTheme.colors.ground),
            )
            RegolithSnackbarHost(snackbar)
        }
    } else if (immersive) {
        Box(modifier.fillMaxSize().then(slide).background(Color.Black).testTag("player_screen")) {
            // Ambient bars (F10). A 2.39:1 film in a 16:9 window, or any film
            // on the near-square inner display, leaves bands the picture does
            // not reach; media3 sizes the video surface to the CONTENT, so
            // those bands belong to us and can carry the film's own colour
            // instead of black. Bars burned into the frames themselves are a
            // different thing and stay exactly as they are — those pixels are
            // the picture. Black stays underneath, so a film whose poster
            // never loaded looks the way it always did.
            AmbientGlow(state.fileId, Modifier.fillMaxSize(), spill = true, light = ambientLight, sample = ambientSample)
            Box(Modifier.fillMaxSize().graphicsLayer { scaleX = pictureScale; scaleY = pictureScale }) { video() }
            // A SurfaceView ignores alpha from a parent layer, so "dimming"
            // is a scrim drawn over it rather than a fade applied to it.
            if (dragDown > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dragDown * 0.45f)))
            // The map's three columns need the width; it stays a landscape lesson.
            if (showGestureMap && landscape) GestureMap(onDismiss = viewModel::dismissGestureMap)
            RegolithSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    } else if (sideBySide) {
        // Two columns, level at the top. The left one is the film with its
        // own controls under it — the same order the portrait player reads
        // in — and the right one is the folder, so what plays next sits
        // beside the picture rather than below a screenful of settings.
        val sideWidth = (windowShape.width * SIDE_COLUMN_FRACTION).coerceIn(300.dp, 460.dp)
        Box(modifier.fillMaxSize().then(slide).testTag("player_screen")) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = pageAlpha }) {
                Box(Modifier.fillMaxSize().background(RegolithTheme.colors.ground))
                AmbientGlow(state.fileId, Modifier.fillMaxSize(), light = ambientLight, sample = ambientSample)
            }
            Row(Modifier.fillMaxSize().systemBarsPadding()) {
                Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = Spacing.s12)) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                            .then(intoSlot)
                            .graphicsLayer { scaleX = pictureScale; scaleY = pictureScale },
                    ) { video() }
                    // The same column the phone draws under its picture, minus
                    // the folder, which has the whole right-hand side to itself.
                    PlayerDetails(
                        modifier = Modifier.weight(1f)
                            .padding(start = Spacing.s8, end = Spacing.s8)
                            .graphicsLayer { alpha = (1f - maxOf(dragUp, dragDown)) * pageAlpha },
                        state = state,
                        cb = chromeCallbacks,
                        orientation = pillOrientation,
                        transfer = transfer,
                        onNudgeA = viewModel::nudgeLoopA,
                        onNudgeB = viewModel::nudgeLoopB,
                        onPlayNext = viewModel::playNext,
                        showNext = false,
                        chapterEditor = editorPanel,
                    )
                }
                Column(
                    Modifier.width(sideWidth).fillMaxHeight().verticalScroll(rememberScrollState())
                        .padding(end = Spacing.s18, top = Spacing.s12, bottom = Spacing.s12)
                        .graphicsLayer { alpha = (1f - maxOf(dragUp, dragDown)) * pageAlpha }
                        .testTag("player_up_next_column"),
                ) {
                    if (reel != null) ReelList(reel, state.reelProgress(), chromeCallbacks.onReelClip) else NextInFolder(state, viewModel::playNext, emptyState = true)
                    Spacer(Modifier.height(Spacing.s30))
                }
            }
            RegolithSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    } else {
        Box(modifier.fillMaxSize().then(slide).testTag("player_screen")) {
        // The ground and its glow on a layer of their own, so the page under
        // the player shows through as the picture shrinks away.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = pageAlpha }) {
            Box(Modifier.fillMaxSize().background(RegolithTheme.colors.ground))
            AmbientGlow(state.fileId, Modifier.fillMaxSize(), light = ambientLight, sample = ambientSample)
        }
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxWidth().statusBarsPadding().aspectRatio(16f / 9f)
                    .then(intoSlot)
                    .graphicsLayer { scaleX = pictureScale; scaleY = pictureScale },
            ) { video() }
            PlayerDetails(
                modifier = Modifier.graphicsLayer {
                    // Up: the details get out of the picture's way. Down: they
                    // go with it, so the whole player reads as one thing being
                    // put away rather than a picture shrinking on a live page.
                    alpha = (1f - maxOf(dragUp, dragDown)) * pageAlpha
                    translationY = dragUp * 60.dp.toPx() + (1f - pageAlpha) * 30.dp.toPx()
                    scaleX = 1f - dragDown * 0.06f
                    scaleY = 1f - dragDown * 0.06f
                },
                state = state,
                cb = chromeCallbacks,
                orientation = pillOrientation,
                transfer = transfer,
                onNudgeA = viewModel::nudgeLoopA,
                onNudgeB = viewModel::nudgeLoopB,
                onPlayNext = viewModel::playNext,
                showNext = true,
                chapterEditor = editorPanel,
            )
        }
        RegolithSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
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
                ambientLight = ambientLight,
                orientation = orientation,
                onSpeed = viewModel::setSpeed,
                onOrientation = viewModel::setOrientation,
                onHardwareDecoding = viewModel::setHardwareDecoding,
                onScrubThumbnails = viewModel::setScrubThumbnails,
                onAutoplayNext = viewModel::setAutoplayNext,
                onAutoplayImmediately = viewModel::setAutoplayImmediately,
                onAmbientLight = viewModel::setAmbientLight,
                onClose = { sheet = null },
                onMakePoster = state.fileId?.takeIf { canMakePoster }?.let { id ->
                    { sheet = null; onMakePoster(id, viewModel.pauseForPoster()) }
                },
            )
        }
        Sheet.Chapters -> PlayerSheetHost(onDismiss = { sheet = null }, testTag = "player_chapters_sheet") {
            ChaptersSheetContent(
                chapters = state.chapters,
                frames = chapterFrames,
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                source = state.chapterSource,
                sync = state.chapterSync,
                syncNote = state.chapterSyncNote,
                onSeek = { ms -> viewModel.seekTo(ms); sheet = null },
                // Editing happens under the picture, so a chosen full screen
                // is stepped out of first — the same thing back would do.
                onEdit = if (viewModel.canEditChapters()) {
                    { sheet = null; if (!forcedFullscreen) fullscreen = false; controlsVisible = true; viewModel.beginChapterEdit() }
                } else null,
                onRevert = if (state.chapterSource == ChapterSource.USER) {
                    { sheet = null; confirmRevert = true }
                } else null,
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
    // Landscape and half-open have no column under the picture, so the
    // editor is a sheet there, with its own timeline to mark on.
    draft?.let { d ->
        if (immersive || flex) {
            PlayerSheetHost(onDismiss = { if (d.dirty) confirmDiscard = true else viewModel.discardChapterEdit() }, testTag = "player_chapter_editor_sheet") {
                editor(d, true)
            }
        }
    }
    if (confirmRevert) {
        val n = state.chapters.size
        ConfirmDialog(
            title = "Revert chapters?",
            body = "Your " + (if (n == 1) "chapter" else "$n chapters") + " on this film go" +
                (if (state.chapterSync == ChapterSyncState.ON_SHARE || state.chapterSync == ChapterSyncState.FROM_SHARE || state.chapterSync == ChapterSyncState.WAITING) ", and the chapter file beside it on the share" else "") +
                ". It goes back to its own markers, or the even split.",
            confirmLabel = "Revert", keepLabel = "Keep mine",
            onConfirm = { viewModel.revertChapters(); confirmRevert = false }, onKeep = { confirmRevert = false },
            testTag = "player_chapters_revert",
        )
    }
    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            body = "What you marked and named since Edit goes. The film keeps the chapters it had.",
            confirmLabel = "Discard", keepLabel = "Keep editing",
            onConfirm = { viewModel.discardChapterEdit(); confirmDiscard = false }, onKeep = { confirmDiscard = false },
            testTag = "player_chapter_discard",
        )
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
    val onChapters: () -> Unit,
    /** Steps the rotation lock on one: Auto -> Portrait -> Landscape -> Auto. */
    val onCycleRotation: () -> Unit,
    /** Start, or retry, the download of this file. */
    val onKeep: () -> Unit,
    /** Cancel the download in flight, or remove the finished copy. */
    val onRemove: () -> Unit,
    /** False for a video the phone already had: there is no share to keep a copy from. */
    val canKeep: Boolean = true,
    /** Scramble what is left to play, or put it back in folder order. */
    val onShuffle: () -> Unit,
    /** Steps the repeat mode on one: off -> all -> one -> off. */
    val onRepeat: () -> Unit,
    /**
     * False while the chapter sheet's pictures are still coming, which keeps
     * the Chapters pill spinning rather than opening onto empty tiles.
     *
     * A value among lambdas, which is not lovely, but it rides here because
     * every chrome already carries this object -- the alternative was the
     * same flag threaded through three chrome signatures and [PillRow].
     */
    val chaptersOpenable: Boolean = true,
    /** A reel's moment picked from its list (Reel.kt). */
    val onReelClip: (Int) -> Unit = {},
    /** Leave the reel for the whole video, carrying on from here. */
    val onWatchFromHere: () -> Unit = {},
    /** Shuffle what is left of the reel, or put it back in order. */
    val onReelShuffle: () -> Unit = {},
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
private fun BoxScope.PortraitChrome(state: PlaybackState, visible: Boolean, scrubPreviewMs: Long?, scrubFrame: android.graphics.Bitmap?, cb: ChromeCallbacks, draft: ChapterDraft?, smooth: SmoothProgress) {
    val colors = RegolithTheme.colors
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            ChromeScrim(landscape = false)
            // A reel's bars run across the top (Reel.kt): the two ways out drop
            // under them with the canvas's 16dp between, rather than touching.
            val top = if (state.reel != null) REEL_CHROME_TOP else 6.dp
            IconCell(R.drawable.rg_ic_arrow_down, "Shrink the player", 22.dp, cb.onBack, "player_back_button", Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = top))
            IconCell(R.drawable.rg_ic_fullscreen, "Full screen", 18.dp, cb.onFullscreen, "player_fullscreen_button", Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = top))
            Transport(state, cb, gap = Spacing.s18, circle = 48.dp, glyph = 26.dp, modifier = Modifier.align(Alignment.Center))
            // A reel has its moments across the top instead of a timeline here.
            if (state.reel == null) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 2.dp)) {
                scrubPreviewMs?.let { ms -> ScrubPreview(ms, scrubFrame, if (state.durationMs > 0) (ms.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f, state.chapterLabelAt(ms)) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                    PositionClock({ scrubPreviewMs ?: smooth.clockMs() }, TextStyles.eyebrow.copy(letterSpacing = 0.sp), colors.ink, Modifier.testTag("player_position"), reserveForMs = state.durationMs)
                    Scrubber(
                        progress = smooth::fraction, durationMs = state.durationMs, buffered = smooth::buffered,
                        loop = state.loop, pendingAMs = state.loopPendingAMs, chapters = draft?.chapters ?: state.chapters,
                        onScrubStart = cb.onScrubStart, onScrub = cb.onScrub, onScrubEnd = cb.onScrubEnd,
                        trackHeight = 3.dp, showKnob = false, onTrackWidth = smooth::onTrackWidth, modifier = Modifier.weight(1f),
                    )
                    Text(formatClock(state.durationMs), style = TextStyles.eyebrow.copy(letterSpacing = 0.sp), color = colors.body, modifier = Modifier.testTag("player_duration"))
                }
            }
        }
    }
}

/**
 * Full-screen chrome (design "Player · landscape"): 18/30/12 padding; back
 * with the title (Michroma 16) and meta line top-left, and nothing else on
 * that edge; the full transport in the middle at 52 · 74 circle · 52 with
 * 40dp gaps; the clocks at
 * 600 13px around the 4dp track with the red knob, and under the track the
 * pills — speed, A–B, Chapters, rotation — with the playback glyph at the
 * far end of the same row.
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
    orientation: PlayerOrientation?,
    transfer: TransferView?,
    draft: ChapterDraft?,
    smooth: SmoothProgress,
) {
    val colors = RegolithTheme.colors
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            ChromeScrim(landscape = true)
            // 30dp gutters are the design's landscape frame. Portrait full
            // screen is 411dp across and the pill row under the timeline does
            // not fit inside them, so it takes the portrait frame's 18.
            val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            val gutter = if (landscape) 30.dp else Spacing.s18
            Column(Modifier.fillMaxSize().padding(start = gutter, end = gutter, top = 18.dp, bottom = 12.dp)) {
                // The header carries the two ways out and the time. The clock
                // sits BESIDE Back rather than centred: dead centre at the top
                // is where a phone puts its camera, and the notch was landing
                // on it. 8dp is the gap FlexChrome already uses between this
                // same glyph and the text next to it -- with the 44dp cell
                // around a 20dp arrow that reads as about 20dp of air.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconCell(R.drawable.rg_ic_back, "Back", 20.dp, cb.onBack, "player_back_button")
                    PlayerClock(Modifier.padding(start = Spacing.s8))
                    Spacer(Modifier.weight(1f))
                    if (canCollapse) {
                        IconCell(R.drawable.rg_ic_fullscreen_exit, "Leave full screen", 18.dp, cb.onFullscreen, "player_fullscreen_button", size = 40.dp)
                    }
                }
                Spacer(Modifier.weight(1f))
                // Everything else is one block at the bottom, in the order it
                // is read: what this is, where you are in it, the transport,
                // then the controls that are about the film rather than about
                // playing it.
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s4)) {
                        DisplayText(
                            state.title,
                            style = TextStyles.screenTitle.copy(fontSize = 16.designSp(), lineHeight = 20.8.designSp()),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        val meta = listOf(state.sourceLabel) + (state.video?.chips ?: emptyList())
                        Text(
                            meta.filter { it.isNotEmpty() }.joinToString(" · "),
                            style = TextStyles.meta12, color = colors.body, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    scrubPreviewMs?.let { ms -> ScrubPreview(ms, scrubFrame, if (state.durationMs > 0) (ms.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f, state.chapterLabelAt(ms)) }
                    // A reel has its moments across the top instead of a timeline.
                    if (state.reel == null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                        PositionClock({ scrubPreviewMs ?: smooth.clockMs() }, TextStyles.buttonSmall, colors.ink, Modifier.testTag("player_position"), reserveForMs = state.durationMs)
                        Scrubber(
                            progress = smooth::fraction, durationMs = state.durationMs, buffered = smooth::buffered,
                            loop = state.loop, pendingAMs = state.loopPendingAMs, chapters = draft?.chapters ?: state.chapters,
                            onScrubStart = cb.onScrubStart, onScrub = cb.onScrub, onScrubEnd = cb.onScrubEnd,
                            trackHeight = 4.dp, showKnob = true, onTrackWidth = smooth::onTrackWidth, modifier = Modifier.weight(1f),
                        )
                        Text(formatClock(state.durationMs), style = TextStyles.buttonSmall, color = colors.body, modifier = Modifier.testTag("player_duration"))
                    }
                    // Previous · play · next dead centre, shuffle and repeat
                    // on the edges. The gap between the three closes up in a
                    // portrait window, where the row has 411dp to work in.
                    Transport(
                        state, cb, gap = if (landscape) 40.dp else Spacing.s18,
                        circle = 74.dp, glyph = 27.dp, cell = 52.dp, modes = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // The pills live UNDER the transport, not up in the header.
                    // Over the top of the picture they sat beside the title,
                    // which read as part of the film's identity; down here they
                    // are what they are — the controls for the thing the
                    // timeline is scrubbing.
                    if (state.reel == null) PillRow(state, cb, onMedia = true, orientation = orientation, transfer = transfer, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** A glyph in a hit cell, white on the picture. */
/**
 * Previous · play · next, at whatever size the layout asks for, with
 * shuffle and repeat bracketing them where there is room ([modes]).
 *
 * There are no ±10s keys. They were two of the five cells and duplicated
 * the gesture everyone already uses — a double-tap on either side of the
 * picture, which stacks and needs no aiming. Losing them is what makes room
 * for shuffle and repeat without the row growing.
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
    /** Shuffle and repeat on the ends. The full-screen player has the width; the 16:9 strip does not. */
    modes: Boolean = false,
) {
    val colors = RegolithTheme.colors
    val keys = @Composable {
        IconCell(R.drawable.rg_ic_skip_previous, "Previous", glyph - 5.dp, cb.onPrevious ?: {}, "player_previous_button", size = cell, enabled = cb.onPrevious != null)
        PlayCircle(state, circle, circle * 0.42f, cb.onTogglePlay)
        IconCell(R.drawable.rg_ic_skip_next, "Next", glyph - 5.dp, cb.onNext ?: {}, "player_next_button", size = cell, enabled = cb.onNext != null)
    }
    if (!modes) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) { keys() }
        return
    }
    // The three keys stay dead centre whatever else is on the row, and the
    // two modes go to the far edges. They are a different kind of control —
    // they change what the row will do next rather than doing it now — and
    // the distance says so without a label.
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        IconCell(
            R.drawable.rg_ic_shuffle,
            if (state.shuffled) "Shuffle is on. Tap to play in order." else "Shuffle what is left to play",
            glyph - 6.dp, cb.onShuffle, "player_shuffle_button", Modifier.align(Alignment.CenterStart), size = cell,
            tint = if (state.shuffled) colors.accent else null,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) { keys() }
        // One button cycling three states, like the rotation pill: two of
        // three are always the answer you did not pick. Red says "not the
        // default", which is the app's word for it everywhere else, and the
        // "1" is the second channel so it never rests on colour.
        IconCell(
            if (state.repeat == RepeatMode.ONE) LucideR.drawable.lucide_ic_repeat_1 else LucideR.drawable.lucide_ic_repeat,
            "Repeat: ${state.repeat.label}. Tap to change.",
            glyph - 6.dp, cb.onRepeat, "player_repeat_button", Modifier.align(Alignment.CenterEnd), size = cell,
            tint = if (state.repeat != RepeatMode.OFF) colors.accent else null,
        )
    }
}

@Composable
private fun IconCell(
    icon: Int,
    description: String,
    iconSize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 44.dp,
    enabled: Boolean = true,
    /** Overrides the white, for a control that is reporting a state as well as offering one. */
    tint: Color? = null,
) {
    Box(
        modifier.size(size).clickable(interactionSource = null, indication = null, enabled = enabled, onClick = onClick).testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = description,
            tint = when {
                !enabled -> RegolithTheme.colors.disabledInk
                tint != null -> tint
                else -> RegolithTheme.colors.ink
            },
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

/**
 * The pills that sit with the timeline, the same row wherever the player is
 * drawn — under the picture on a phone, in the unfolded column, over the
 * picture in full screen. Speed, A–B (while armed, or over the picture),
 * Chapters and the rotation lock on the left; a gap; the download and the
 * settings glyph on the right, where a thumb resting on the edge finds
 * them. One definition, because three copies of this row had grown three
 * different sets of pills.
 *
 * There is no decoder pill. HW/SW was a pill you could only ever tap to
 * open the sheet it was reporting, for a setting that already has a
 * permanent home in app Settings — three entrances to one switch. The
 * pills that remain each DO something on tap.
 *
 * @param orientation the current rotation lock, or null where Android does
 *   the deciding and the pill would be a control that changes nothing.
 * @param compact one plain run of pills with no gap, for a header that
 *   shares its row with the title (flex mode).
 */
@Composable
private fun PillRow(
    state: PlaybackState,
    cb: ChromeCallbacks,
    onMedia: Boolean,
    orientation: PlayerOrientation?,
    transfer: TransferView?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    // There is no speed pill. Speed lives in the playback sheet, which is one
    // tap away at the end of this very row, and a pill that only ever opened
    // that sheet was a second door to one setting.
    val left = @Composable {
        // Always here, at every width. It used to appear under the picture
        // only once a loop was already on its way, which was a width
        // compromise from when this row was one scrolling group with a speed
        // pill in it — and it meant a loop could only be STARTED in full
        // screen, so the feature was invisible to anyone who never went
        // there. The row is three groups now and has the room.
        //
        // Unarmed it is a glyph alone: the label only earns its width once it
        // has something to say ("A ·", "A–B").
        val armed = state.loop != null || state.loopPendingAMs != null
        PillButton(
            text = when {
                !armed -> ""
                state.loop == null -> "A ·"
                else -> "A–B"
            },
            selected = state.loop != null, onClick = cb.onLoopTap, onLongClick = cb.onLoopClear, onMedia = onMedia,
            icon = R.drawable.rg_ic_loop, testTag = "player_loop_pill",
            contentDescription = if (armed) null else "A–B loop. Tap to set point A.",
        )
        // One pill that cycles rather than three that sit there, because two
        // of the three are always the answer you did not pick.
        //
        // Glyph only, no label: "Landscape" spelled out pushed the row past
        // 411dp and the word was the first thing clipped. The three glyphs
        // are a set — a device standing up, a device lying down, and both of
        // them for Auto — and red says "locked", which is the app's word for
        // "not the default" everywhere else. The playback sheet keeps the
        // written three-way for anyone who wants to read it.
        if (orientation != null) {
            PillButton(
                text = "",
                onClick = cb.onCycleRotation,
                selected = orientation != PlayerOrientation.AUTO,
                onMedia = onMedia,
                icon = when (orientation) {
                    PlayerOrientation.AUTO -> LucideR.drawable.lucide_ic_tablet_smartphone
                    PlayerOrientation.PORTRAIT -> LucideR.drawable.lucide_ic_rectangle_vertical
                    PlayerOrientation.LANDSCAPE -> LucideR.drawable.lucide_ic_rectangle_horizontal
                },
                contentDescription = "Rotation: ${orientation.label}. Tap to change.",
                testTag = "player_rotation_pill",
            )
        }
    }
    // The pill is drawn as soon as the film is loaded, but it spins until the
    // list has SETTLED — the container has been read and the runtime is
    // known — and until the sheet's PICTURES have settled too. Opening before
    // the list settled meant a sheet that resized itself as the parts were
    // recounted underneath it; opening before the pictures meant a grid of
    // empty tiles filling in one at a time (see [chapterFramesSettled], which
    // is also where the ways out of waiting are).
    val centre = @Composable {
        PillButton(
            text = "Chapters",
            onClick = { if (state.chaptersReady && cb.chaptersOpenable) cb.onChapters() },
            onMedia = onMedia,
            icon = R.drawable.rg_ic_chapters,
            testTag = "player_chapters_pill",
            loading = !state.chaptersReady || !cb.chaptersOpenable,
        )
    }
    val right = @Composable {
        if (state.fileId != null && state.fileId != com.regolith.ui.navigation.RegolithKey.Player.EXTERNAL && cb.canKeep) {
            DownloadPill(transfer, onMedia, cb.onKeep, cb.onRemove)
        }
        PillButton(
            text = "", onClick = cb.onOpenPlayback, onMedia = onMedia, icon = R.drawable.rg_ic_sliders,
            contentDescription = "Playback settings", testTag = "player_playback_button",
        )
    }
    if (compact) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.s8), verticalAlignment = Alignment.CenterVertically) {
            left()
            centre()
            right()
        }
        return
    }
    // Three groups, and Chapters dead centre: the two weighted cells split
    // whatever is left equally, so the middle is centred on the ROW rather
    // than on what happens to be beside it.
    //
    // The left cell scrolls if it must. Without that its last pill is not
    // dropped, it is SQUEEZED: the row gives the Text zero width and you get
    // a pill with nothing written on it.
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) { left() }
        centre()
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s8, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) { right() }
    }
}

/**
 * The download, as one glyph: an arrow to start it, a ring while it runs,
 * a check once the copy is here. Hold to cancel or remove — a single tap
 * that could throw away ten gigabytes is not a tap anyone means.
 */
@Composable
private fun DownloadPill(transfer: TransferView?, onMedia: Boolean, onKeep: () -> Unit, onRemove: () -> Unit) {
    when (transfer?.status) {
        null, TransferStatus.FAILED -> PillButton(
            text = "", onClick = onKeep, onMedia = onMedia, icon = R.drawable.rg_ic_download,
            contentDescription = if (transfer == null) "Keep on this device" else "Download failed. Tap to try again.",
            testTag = "player_download_pill",
        )
        TransferStatus.DONE -> PillButton(
            text = "", onClick = {}, onLongClick = onRemove, onMedia = onMedia, icon = R.drawable.rg_ic_check,
            contentDescription = "On this device. Hold to remove.", testTag = "player_download_pill",
        )
        TransferStatus.QUEUED -> PillButton(
            text = "", onClick = {}, onLongClick = onRemove, onMedia = onMedia, loading = true,
            contentDescription = "Queued for download. Hold to cancel.", testTag = "player_download_pill",
        )
        TransferStatus.RUNNING, TransferStatus.PAUSED -> PillButton(
            text = "", onClick = {}, onLongClick = onRemove, onMedia = onMedia, progress = transfer.fraction,
            contentDescription = "Downloading, ${(transfer.fraction * 100).roundToInt()}%. Hold to cancel.", testTag = "player_download_pill",
        )
    }
}

/**
 * Whether locking the rotation would do anything here. Android 16 stopped
 * honouring an app's orientation request on large screens, so on the Fold's
 * inner display the lock is silently a no-op — see [LARGE_SCREEN_DP]. The
 * playback sheet greys its row and explains; a pill has nowhere to put that
 * sentence, so it simply is not drawn.
 */
@Composable
private fun rotationLockable(): Boolean =
    LocalConfiguration.current.smallestScreenWidthDp < LARGE_SCREEN_DP

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
 * Everything under the picture that is not the picture (design "Player ·
 * portrait"): the title in Michroma 17 over its meta line, the pill row,
 * and — when this column is the only one — NEXT IN THIS FOLDER as 56dp rows
 * with an 84x47 thumb at 10dp corners. With a loop set the top of the
 * column IS the loop (design frame 29): span, points, clear.
 *
 * ONE composable for the phone, the unfolded portrait and the unfolded
 * landscape's left column. They used to be two: the phone's column and a
 * hand-built copy beside the landscape picture, and the copy had grown its
 * own set of pills and an inline settings panel the phone never had, so
 * folding the device changed what the player offered. Now the layouts
 * decide only where the folder goes ([showNext]); what the column holds is
 * decided here, once. The playback settings are a sheet everywhere for the
 * same reason — one place, one shape.
 */
@Composable
private fun PlayerDetails(
    modifier: Modifier,
    state: PlaybackState,
    cb: ChromeCallbacks,
    orientation: PlayerOrientation?,
    transfer: TransferView?,
    onNudgeA: (Long) -> Unit,
    onNudgeB: (Long) -> Unit,
    onPlayNext: (Long) -> Unit,
    /** False where the folder has a column of its own beside the picture. */
    showNext: Boolean,
    /** The chapter editor while it is open; it takes the loop's slot, and the loop's precedence. */
    chapterEditor: (@Composable () -> Unit)? = null,
) {
    val loop = state.loop
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = Spacing.s18, end = Spacing.s18, top = Spacing.s18)
            .testTag(if (chapterEditor != null) "player_chapter_editor_panel" else if (loop != null) "player_loop_panel" else "player_details"),
        verticalArrangement = Arrangement.spacedBy(Spacing.s12),
    ) {
        val reel = state.reel
        when {
            reel != null -> ReelDetails(reel, cb.onWatchFromHere, cb.onReelShuffle)
            chapterEditor != null -> chapterEditor()
            loop != null -> AbLoopSheetContent(loop = loop, positionMs = state.positionMs, durationMs = state.durationMs, onNudgeA = onNudgeA, onNudgeB = onNudgeB, onClear = cb.onLoopClear)
            else -> {
                TitleBlock(state)
                PillRow(state, cb, onMedia = false, orientation = orientation, transfer = transfer, modifier = Modifier.fillMaxWidth())
            }
        }
        // The folder still follows a loop — the loop is about this film, not
        // about what comes after it. A reel lists its moments instead.
        if (showNext) {
            if (reel != null) ReelList(reel, state.reelProgress(), cb.onReelClip) else NextInFolder(state, onPlayNext)
        }
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
 * and clamped to the track, with the chapter's name and the time in pills
 * beneath — the name is what tells you where you are; the clock is how
 * far. The slot keeps its height either way so the track does not jump.
 */
@Composable
private fun ScrubPreview(ms: Long, frame: android.graphics.Bitmap?, fraction: Float, chapter: String? = null) {
    val colors = RegolithTheme.colors
    val previewW = 160.dp
    val previewH = 90.dp
    BoxWithConstraints(Modifier.fillMaxWidth().height(previewH + 50.dp)) {
        val width = if (frame != null || chapter != null) previewW else 80.dp
        val left = (maxWidth * fraction - width / 2).coerceIn(0.dp, (maxWidth - width).coerceAtLeast(0.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.s4), modifier = Modifier.align(Alignment.BottomStart).offset(x = left).width(width)) {
            if (frame != null) {
                Image(
                    bitmap = frame.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(previewW, previewH).clip(CardShape).border(1.dp, colors.onMediaCircleBorder, CardShape).testTag("player_scrub_preview"),
                )
            }
            if (chapter != null) {
                Text(
                    chapter, style = TextStyles.buttonSmall, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.background(colors.overArt, PillShape).padding(horizontal = Spacing.s8, vertical = Spacing.s2).testTag("player_scrub_chapter"),
                )
            }
            Text(formatClock(ms), style = TextStyles.chipOverArt, color = colors.ink, modifier = Modifier.background(colors.overArt, PillShape).padding(horizontal = Spacing.s8, vertical = Spacing.s2).testTag("player_scrub_time"))
        }
    }
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
 * header. Back, the title with its meta line, the speed and A–B pills and
 * the playback glyph. Standing on a table there is no timeline up here to
 * hang them under, the way [FullChrome] now does, and no rotation worth
 * locking. The
 * timeline and the transport are in the deck below the hinge, where the
 * hands are when the device is standing on a table.
 */
@Composable
private fun BoxScope.FlexChrome(state: PlaybackState, visible: Boolean, cb: ChromeCallbacks, transfer: TransferView?, smooth: SmoothProgress) {
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
                // No rotation pill: a half-open foldable is a large screen,
                // and Android does its own deciding there.
                PillRow(state, cb, onMedia = true, orientation = null, transfer = transfer, compact = true)
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
    draft: ChapterDraft?,
    smooth: SmoothProgress,
    modifier: Modifier = Modifier,
) {
    val colors = RegolithTheme.colors
    // While a drag is live the strip follows the finger, which is what
    // replaces the floating preview the phone shows.
    val here = scrubPreviewMs ?: state.positionMs
    Column(
        modifier.padding(horizontal = Spacing.s30).padding(top = Spacing.s18, bottom = Spacing.s12).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(Spacing.s18),
    ) {
        if (strip.isNotEmpty()) {
            Filmstrip(
                frames = strip,
                positionMs = here,
                onSeek = { ms -> if (state.durationMs > 0) cb.onScrubEnd(ms.toFloat() / state.durationMs) },
                testTag = "player_strip",
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s12)) {
            PositionClock({ scrubPreviewMs ?: smooth.clockMs() }, TextStyles.buttonSmall, colors.ink, Modifier.testTag("player_position"), reserveForMs = state.durationMs)
            // The filmstrip is this deck's preview, so the chapter's name
            // goes beside the clock rather than in a floating card.
            if (scrubPreviewMs != null) state.chapterLabelAt(here)?.let { label ->
                Text(label, style = TextStyles.buttonSmall, color = colors.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp).testTag("player_scrub_chapter"))
            }
            Scrubber(
                progress = smooth::fraction, durationMs = state.durationMs, buffered = smooth::buffered,
                loop = state.loop, pendingAMs = state.loopPendingAMs, chapters = draft?.chapters ?: state.chapters,
                onScrubStart = cb.onScrubStart, onScrub = cb.onScrub, onScrubEnd = cb.onScrubEnd,
                trackHeight = 4.dp, showKnob = true, onTrackWidth = smooth::onTrackWidth, modifier = Modifier.weight(1f),
            )
            Text(formatClock(state.durationMs), style = TextStyles.buttonSmall, color = colors.body, modifier = Modifier.testTag("player_duration"))
        }
        // The deck is the full width of a half-open fold, so it gets the
        // whole row the full-screen player does.
        Transport(
            state, cb, gap = Spacing.s18, circle = 74.dp, glyph = 27.dp, cell = 52.dp,
            modes = true, modifier = Modifier.fillMaxWidth(),
        )
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

/**
 * The ambient light behind and beside the picture: a bias light, the way a
 * strip behind a TV works. A 2:39 film then sits in its own colour instead
 * of a black band, which is the whole point.
 *
 * Color bleed is its own drawing ([ColorBleedLight]): colour from the
 * picture's edges, shone outward. Everything below is Mirror, and Off.
 *
 * Mirror is scaled out so nothing has an edge, blurred, and dimmed under a
 * scrim: the recipe is [ArtworkLight], which a collection's profile shares.
 *
 * Two layers. The base coat is the title's own backdrop, which costs no
 * read over the share and is there before the first frame is drawn — and is
 * the whole of it when the light is switched off. Over it, [frame]: a 32×18
 * sample of the picture that is on screen right now, from
 * [rememberAmbientLight], blown up to fill the window.
 *
 * Because it is the frame itself and not a colour picked out of it, the
 * left of the screen glows what is on the left of the shot; a sunset over
 * water lights blue below and orange above on its own, with no zones to
 * define. And because the sample is 32 px across, the upscale alone is most
 * of the softness — the blur only has to take the last edges off.
 */
@Composable
private fun AmbientGlow(
    fileId: Long?,
    modifier: Modifier = Modifier,
    spill: Boolean = false,
    light: AmbientLight = AmbientLight.DEFAULT,
    sample: AmbientSample? = null,
) {
    if (fileId == null) return
    if (light == AmbientLight.COLOR_BLEED) {
        ColorBleedLight(sample as? AmbientSample.Edges, modifier, spill = spill)
        return
    }
    val frame = (sample as? AmbientSample.Frame)?.bitmap
    // The backdrop, not the poster: this sits behind a 16:9 picture and
    // fills the bands beside it, so a 2:3 centre crop would show the
    // middle strip of the frame stretched across the whole window.
    val request = remember(fileId) { ArtworkRequest(ArtworkOwner.File(fileId), ArtworkKind.BACKDROP) }
    Box(modifier.clipToBounds()) {
        // The sample goes inside the same blur as the backdrop, so the two
        // mix as light rather than as two pictures (ArtworkLight).
        ArtworkLight(request, Modifier.fillMaxSize(), testTag = "player_ambient_glow") { lift ->
            // No cross-fade here on purpose. The light is smoothed where it
            // is sampled, in 576 pixels; animating a full-screen layer's
            // alpha instead would re-run the blur on every frame of every
            // change and make it the most expensive thing on the screen.
            frame?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = lift,
                    modifier = Modifier.fillMaxSize().testTag("player_ambient_frame"),
                )
            }
        }
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


/**
 * How far a middle drag must travel, as a fraction of the picture's height,
 * before it enters or leaves full screen. A flick commits at any distance;
 * this is the floor for a slow, deliberate drag, and it is what stops a
 * stray finger from flipping the layout.
 */
private const val FULLSCREEN_DRAG_FRACTION = 0.12f

/**
 * How far into shrinking the mini player is handed the picture: within 1% of
 * its spot, about 60ms before the end, which leaves the mini player time to be
 * drawn before the player is gone. Sooner and the two pictures stood apart.
 */
private const val MINI_HANDOVER_AT = 0.99f

/** Let go slowly past this much of the way, the swipe down finishes into the mini player; short of it, back up. */
private const val SHRINK_SETTLE_FRACTION = 0.5f

/** A flick, in dp a second, decides the swipe down by its direction alone. */
private const val SHRINK_FLICK_DP_PER_S = 1_000

/** A step of the swipe down's back gesture, [progress] of the way into the mini player. */
private fun shrinkEvent(progress: Float) = NavigationEvent(touchX = 0f, touchY = 0f, progress = progress, swipeEdge = NavigationEvent.EDGE_NONE)

/**
 * When this easing reaches [value]: its inverse, found by halving, for a
 * curve that only rises. A back gesture's progress is the transition's time;
 * this turns where the picture should be into that time.
 */
private fun Easing.timeFor(value: Float): Float {
    var low = 0f
    var high = 1f
    repeat(24) {
        val mid = (low + high) / 2f
        if (transform(mid) < value) low = mid else high = mid
    }
    return (low + high) / 2f
}

/** The film's still fading off its first frame: quick, so it reads as the picture arriving, not a dissolve. */
private const val STILL_FADE_MS = 160

/** How long a reel's moment shows its name as it starts. */
private const val REEL_CAPTION_MS = 1_500L

/**
 * Where the portrait chrome's top glyphs sit in a reel: 12dp lower than a
 * film's, so the bars at 10dp (3dp tall) have 16dp of picture under them
 * before the 22dp arrow in its 44dp cell starts.
 */
private val REEL_CHROME_TOP = 18.dp

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
