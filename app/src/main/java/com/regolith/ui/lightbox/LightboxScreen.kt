package com.regolith.ui.lightbox

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import com.composables.icons.lucide.R as LucideR
import com.regolith.R
import com.regolith.data.pictures.PictureOriginal
import com.regolith.ui.adaptive.LocalWindowShape
import com.regolith.ui.components.ArtworkImage
import com.regolith.ui.components.LocalSpoof
import com.regolith.ui.components.PictureScrim
import com.regolith.ui.components.ScrimEdge
import com.regolith.ui.components.onPicture
import com.regolith.ui.components.RegolithSheet
import com.regolith.ui.components.posterFlight
import com.regolith.ui.library.PictureTile
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.util.formatBytes
import com.regolith.ui.util.formatDateTime
import kotlinx.coroutines.launch
import androidx.compose.runtime.DisposableEffect
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.regolith.ui.components.FlightEasing
import com.regolith.ui.components.timeFor

/**
 * The lightbox (the canvas "Images on the Share", Light-Open, Light-Zoom,
 * Light-Inner): one picture at a time on black, flown in from the tile that
 * was tapped and back into it when closed. Swipe sideways for its
 * neighbours, pinch or double-tap to zoom, swipe down to send it back.
 *
 * A tap brings the chrome in and out: the name and "3 of 86" at the top,
 * when it was taken and how big it is at the foot. The inner display adds a
 * strip of the album along the bottom, to jump about in it.
 */
@Composable
fun LightboxScreen(
    viewModel: LightboxViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pictures = state.pictures
    Box(modifier.fillMaxSize().testTag("lightbox_screen")) {
        if (!state.loaded) return@Box
        if (pictures.isEmpty()) {
            // Every picture went (deleted or moved meanwhile): nothing to show.
            LaunchedEffect(Unit) { onClose() }
            return@Box
        }
        Lightbox(state, viewModel.pictureId, onClose)
    }
}

@Composable
private fun Lightbox(state: LightboxUiState, startId: Long, onClose: () -> Unit) {
    val pictures = state.pictures
    val colors = RegolithTheme.colors
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val wide = LocalWindowShape.current.wide
    val start = remember { pictures.indexOfFirst { it.pictureId == startId }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = start) { pictures.size }
    val current = pictures.getOrNull(pager.currentPage) ?: pictures.first()
    val zooms = remember { HashMap<Long, PictureZoom>() }
    fun zoomOf(id: Long) = zooms.getOrPut(id) { PictureZoom() }
    var chrome by rememberSaveable { mutableStateOf(true) }
    var details by rememberSaveable { mutableStateOf(false) }
    val focus = LocalPictureFocus.current

    // --- The swipe down, back into the tile. It drives the very transition
    // Back plays (lightboxScreen), through a back gesture of the lightbox's
    // own, as the player's swipe into the mini player does: the picture flies
    // home under the finger, the black fades, and the album shows through
    // beneath it. Let go past [PULL_SETTLE] of the way, or flicked down, it
    // finishes; short of it, or flicked up, it comes back.
    val navEvents = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
    val swipeInput = remember { DirectNavigationEventInput() }
    DisposableEffect(navEvents) {
        navEvents?.addInput(swipeInput)
        onDispose { navEvents?.removeInput(swipeInput) }
    }
    // How far the picture has been pulled down, in pixels, while the swipe lasts.
    var pull by remember { mutableFloatStateOf(0f) }
    var swiping by remember { mutableStateOf(false) }

    // The picture on screen is the one to land in its tile when this closes
    // (PictureFocus), and a picture swiped away goes back to fitted.
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page ->
            focus?.pictureId = pictures.getOrNull(page)?.pictureId
            pictures.forEachIndexed { i, p -> if (i != page) zooms[p.pictureId]?.reset() }
        }
    }

    // Closing zoomed in would fly the picture from its fitted place, not
    // where it is drawn: it is put back first, at once.
    val close = {
        zoomOf(current.pictureId).reset()
        onClose()
    }
    // Back on a zoomed picture zooms it out, as a second look; back on a
    // fitted one is Navigation's own, so the system's back swipe flies the
    // picture home under the thumb like the swipe down does.
    val currentZoomed = zoomOf(current.pictureId).zoomed
    BackHandler(enabled = currentZoomed) { scope.launch { zoomOf(current.pictureId).toggle(androidx.compose.ui.geometry.Offset.Zero) } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Half the screen's height carries the picture all the way home.
        val travelPx = with(density) { maxHeight.toPx() } * PULL_TRAVEL
        val flickPxPerS = with(density) { FLING_CLOSES.toPx() }
        Box(Modifier.fillMaxSize().background(Color.Black))

        HorizontalPager(
            state = pager,
            key = { pictures[it].pictureId },
            userScrollEnabled = !currentZoomed && !swiping,
            beyondViewportPageCount = 1,
            pageSpacing = Spacing.s18,
            modifier = Modifier.fillMaxSize().testTag("lightbox_pager"),
        ) { page ->
            val picture = pictures[page]
            val zoom = zoomOf(picture.pictureId)
            val isCurrent = page == pager.currentPage
            val dragState = rememberDraggableState { delta ->
                pull = (pull + delta).coerceAtLeast(0f)
                if (!swiping && pull > 0f) {
                    swiping = true
                    chrome = false
                    swipeInput.backStarted(backEvent(0f))
                }
                if (swiping) swipeInput.backProgressed(backEvent(FlightEasing.timeFor((pull / travelPx).coerceIn(0f, 1f))))
            }
            PicturePage(
                picture = picture,
                zoom = zoom,
                // Only the picture on screen flies, or a neighbour just off it
                // would fly from off the screen into its own tile.
                flies = page == pager.settledPage,
                onTap = { chrome = !chrome },
                onEdge = { forward ->
                    val next = page + if (forward) 1 else -1
                    if (next in pictures.indices) scope.launch { pager.animateScrollToPage(next) }
                },
                modifier = Modifier.draggable(
                    state = dragState,
                    orientation = Orientation.Vertical,
                    enabled = isCurrent && !zoom.zoomed && navEvents != null,
                    onDragStopped = { velocity ->
                        if (swiping) {
                            swiping = false
                            val carried = (pull / travelPx).coerceIn(0f, 1f)
                            pull = 0f
                            val finish = when {
                                velocity > flickPxPerS -> true
                                velocity < -flickPxPerS -> false
                                else -> carried >= PULL_SETTLE
                            }
                            if (finish) {
                                zoom.reset()
                                swipeInput.backCompleted()
                            } else {
                                swipeInput.backCancelled()
                                chrome = true
                            }
                        }
                    },
                ),
            )
        }

        AnimatedVisibility(
            visible = chrome && !swiping,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            LightboxTopBar(
                picture = current,
                position = "${pager.currentPage + 1} of ${pictures.size}" + if (state.album.isNotEmpty()) " · ${state.album}" else "",
                wide = wide,
                onClose = close,
                onDetails = { details = true },
            )
        }
        AnimatedVisibility(
            visible = chrome && !swiping,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            LightboxFoot(
                picture = current,
                pictures = pictures,
                page = pager.currentPage,
                wide = wide,
                onDetails = { details = true },
                onJump = { scope.launch { pager.animateScrollToPage(it) } },
            )
        }
    }

    if (details) PictureDetails(current, state.album) { details = false }
}

/**
 * One picture, fitted to the page and zoomable. Its box is the picture's
 * own shape, so a tile's flight lands exactly on it ([flies]); the small
 * thumbnail draws at once, and the picture itself on top as it arrives
 * ([PictureOriginal]), decoded large enough to stay sharp a few steps into
 * a zoom.
 */
@Composable
private fun PicturePage(
    picture: PictureTile,
    zoom: PictureZoom,
    flies: Boolean,
    onTap: () -> Unit,
    onEdge: (forward: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val edgePx = with(density) { EDGE_PUSH.toPx() }
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .pointerInput(zoom) { zoomGestures(zoom, scope, edgePx, onEdge) }
            .pointerInput(zoom) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { at -> scope.launch { zoom.toggle(at) } },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val pageWidth = maxWidth
        val pageHeight = maxHeight
        val fitted = fit(picture.aspect, pageWidth, pageHeight)
        zoom.page = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        zoom.picture = with(density) { Size(fitted.first.toPx(), fitted.second.toPx()) }
        Box(
            Modifier
                .size(fitted.first, fitted.second)
                .then(if (flies) Modifier.posterFlight(picture.artwork.owner) else Modifier)
                .graphicsLayer {
                    scaleX = zoom.scale
                    scaleY = zoom.scale
                    translationX = zoom.offset.x
                    translationY = zoom.offset.y
                }
                .testTag("lightbox_picture_${picture.pictureId}"),
        ) {
            ArtworkImage(picture.artwork, Modifier.fillMaxSize(), fallbackLabel = picture.name, contentScale = ContentScale.Fit)
            WholePicture(picture, pageWidth, pageHeight, Modifier.fillMaxSize())
        }
    }
}

/**
 * The picture itself, off the share whole ([PictureOriginal]), or spoof
 * mode's stand-in for it. Asked for at twice the screen, at most 4096
 * across: sharp through a double tap's zoom without decoding a 50 MP photo
 * at full size.
 */
@Composable
private fun WholePicture(picture: PictureTile, pageWidth: Dp, pageHeight: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val spoof = LocalSpoof.current
    val longest = with(density) { maxOf(pageWidth, pageHeight).roundToPx() }
    val target = (longest * 2).coerceAtMost(MAX_DECODE)
    val model = spoof?.picture(picture.pictureId) ?: PictureOriginal(picture.pictureId, "${picture.sizeBytes}-${picture.modifiedAtMs}")
    val request = remember(model, target) {
        ImageRequest.Builder(context)
            .data(model)
            .size(target, target)
            .precision(Precision.INEXACT)
            .crossfade(true)
            .build()
    }
    AsyncImage(model = request, contentDescription = picture.title, modifier = modifier, contentScale = ContentScale.Fit)
}

/** The picture's top bar: close, its name, and where it is in the album; on a wide window, Details beside. */
@Composable
private fun LightboxTopBar(picture: PictureTile, position: String, wide: Boolean, onClose: () -> Unit, onDetails: () -> Unit) {
    val colors = RegolithTheme.colors
    // A wash dark enough to read over a white sky (PictureScrim).
    PictureScrim(ScrimEdge.TOP) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 6.dp, end = Spacing.s8, top = Spacing.s8, bottom = Spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s4),
    ) {
        Box(
            Modifier.size(44.dp).clip(PillShape).clickable(onClick = onClose).testTag("lightbox_close_button"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.rg_ic_arrow_down), contentDescription = "Close", tint = colors.ink, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
            Text(
                picture.title, style = TextStyles.rowLabel.onPicture(), color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("lightbox_title"),
            )
            Text(
                if (wide) listOfNotNull(position, picture.takenAtMs?.let(::formatDateTime)).joinToString(" · ") else position,
                style = TextStyles.meta.onPicture(), color = colors.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("lightbox_position"),
            )
        }
        if (wide) LightboxAction("Details", LucideR.drawable.lucide_ic_info, onDetails, "lightbox_details_button", inline = true)
    }
    }
}

/**
 * The foot: when it was taken, its size and its weight on one line, and
 * the picture's actions under it (on a phone; a wide window has them up top,
 * and a strip of the album here instead).
 */
@Composable
private fun LightboxFoot(
    picture: PictureTile,
    pictures: List<PictureTile>,
    page: Int,
    wide: Boolean,
    onDetails: () -> Unit,
    onJump: (Int) -> Unit,
) {
    val colors = RegolithTheme.colors
    PictureScrim(ScrimEdge.BOTTOM) {
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = Spacing.s18, end = Spacing.s18, top = Spacing.s8, bottom = Spacing.s18),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s12),
    ) {
        Text(
            pictureFacts(picture, withDate = !wide),
            style = TextStyles.meta12.onPicture(), color = colors.inkSoft, textAlign = TextAlign.Center,
            modifier = Modifier.testTag("lightbox_facts"),
        )
        if (wide) {
            Filmstrip(pictures, page, onJump)
        } else {
            LightboxAction("Details", LucideR.drawable.lucide_ic_info, onDetails, "lightbox_details_button")
        }
    }
    }
}

/** "14 Jul 2024, 20:41 · 4032 × 3024 · 4.2 MB", the date left out where the bar above already says it. */
private fun pictureFacts(picture: PictureTile, withDate: Boolean): String = listOfNotNull(
    if (withDate) picture.takenAtMs?.let(::formatDateTime) else null,
    if (picture.width != null && picture.height != null) "${picture.width} × ${picture.height}" else null,
    formatBytes(picture.sizeBytes),
    picture.camera.takeIf { !withDate },
).joinToString(" · ")

/** One of the lightbox's actions: a frosted tile with its glyph over its name, or a pill on a wide window's bar ([inline]). */
@Composable
private fun LightboxAction(label: String, icon: Int, onClick: () -> Unit, testTag: String, inline: Boolean = false) {
    val colors = RegolithTheme.colors
    val shape = if (inline) PillShape else RoundedCornerShape(16.dp)
    val base = Modifier
        .clip(shape)
        .background(Color.White.copy(alpha = 0.06f))
        .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
        .clickable(onClick = onClick)
        .testTag(testTag)
    if (inline) {
        Row(base.height(40.dp).padding(horizontal = Spacing.s12), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
            Icon(painterResource(icon), contentDescription = null, tint = colors.ink, modifier = Modifier.size(16.dp))
            Text(label, style = TextStyles.buttonSmall, color = colors.ink)
        }
    } else {
        Column(base.width(104.dp).height(56.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = colors.ink, modifier = Modifier.size(16.dp))
            Text(label, style = TextStyles.chipOverArt, color = colors.ink, modifier = Modifier.padding(top = 5.dp))
        }
    }
}

/** The inner display's strip of the album: the picture on screen ringed, a tap jumps to another. */
@Composable
private fun Filmstrip(pictures: List<PictureTile>, page: Int, onJump: (Int) -> Unit) {
    val colors = RegolithTheme.colors
    val strip = rememberLazyListState()
    LaunchedEffect(page) { strip.animateScrollToItem((page - STRIP_LEAD).coerceAtLeast(0)) }
    LazyRow(
        state = strip,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s8),
        modifier = Modifier.testTag("lightbox_filmstrip"),
    ) {
        itemsIndexed(pictures, key = { _, p -> p.pictureId }) { index, picture ->
            val on = index == page
            Box(
                Modifier
                    .size(width = 52.dp, height = 52.dp)
                    .then(if (on) Modifier.border(2.dp, colors.ink, RoundedCornerShape(8.dp)).padding(2.dp) else Modifier)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onJump(index) }
                    .testTag("lightbox_strip_${picture.pictureId}"),
            ) {
                ArtworkImage(picture.artwork, Modifier.fillMaxSize(), fallbackLabel = picture.name)
            }
        }
    }
}

/** Details: where the picture lives, when it was taken and on what, and how big it is. */
@Composable
private fun PictureDetails(picture: PictureTile, album: String, onDismiss: () -> Unit) {
    val colors = RegolithTheme.colors
    RegolithSheet(title = picture.name, onDismiss = onDismiss, testTag = "lightbox_details_sheet") {
        val rows = listOfNotNull(
            "Taken" to (picture.takenAtMs?.let(::formatDateTime) ?: "Not in the picture"),
            "File date" to formatDateTime(picture.modifiedAtMs),
            picture.width?.let { w -> "Size" to "$w × ${picture.height} · ${formatBytes(picture.sizeBytes)}" } ?: ("Size" to formatBytes(picture.sizeBytes)),
            picture.camera?.let { "Camera" to it },
            "In" to listOf(album, picture.relPath.substringBeforeLast('/', "")).filter { it.isNotEmpty() }.distinct().joinToString(" · ").ifEmpty { "The share's top folder" },
            picture.videoName?.let { "Picture of" to it },
            "Poster" to "This collection's poster".takeIf { picture.poster },
        ).filter { it.second != null }
        Column(Modifier.padding(top = Spacing.s8, bottom = Spacing.s8), verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
            for ((label, value) in rows) {
                Row(Modifier.fillMaxWidth()) {
                    Text(label, style = TextStyles.meta12, color = colors.metadata, modifier = Modifier.width(96.dp))
                    Text(value ?: "", style = TextStyles.meta12, color = colors.ink, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** A step of the swipe down's back gesture, [progress] of the way home. */
private fun backEvent(progress: Float) = NavigationEvent(touchX = 0f, touchY = 0f, progress = progress, swipeEdge = NavigationEvent.EDGE_NONE)

/** The picture's box fitted inside [width] × [height] at its [aspect]. */
private fun fit(aspect: Float, width: Dp, height: Dp): Pair<Dp, Dp> {
    val wide = aspect >= width / height
    return if (wide) width to width / aspect else height * aspect to height
}

/** How far down the screen, as a fraction of its height, a pull carries the picture all the way home. */
private const val PULL_TRAVEL = 0.5f

/** Let go past this much of the way home, the picture goes on into its tile; short of it, it comes back. */
private const val PULL_SETTLE = 0.3f

/** How fast a flick, in dp a second, decides the swipe by its direction alone. */
private val FLING_CLOSES = 1000.dp

/** How far a zoomed picture is pushed past its side before the swipe goes on to the next one. */
private val EDGE_PUSH = 64.dp

/** The biggest a picture is decoded, on its longer side. */
private const val MAX_DECODE = 4096

/** The strip keeps the picture on screen this many places from its start. */
private const val STRIP_LEAD = 3
