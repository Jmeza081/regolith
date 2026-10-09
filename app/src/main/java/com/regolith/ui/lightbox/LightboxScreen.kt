package com.regolith.ui.lightbox

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.R as LucideR
import com.regolith.R
import com.regolith.data.pictures.PictureOriginal
import com.regolith.ui.adaptive.LocalWindowShape
import com.regolith.ui.components.ArtworkImage
import com.regolith.ui.components.PictureScrim
import com.regolith.ui.components.WholePicture
import com.regolith.ui.components.ScrimEdge
import com.regolith.ui.components.SwipeToClose
import com.regolith.ui.components.rememberSwipeToClose
import com.regolith.ui.components.swipeToClose
import com.regolith.ui.components.onPicture
import com.regolith.ui.components.FileActionsHost
import com.regolith.ui.components.RegolithSheet
import com.regolith.ui.components.SheetChoice
import com.regolith.domain.media.PictureSaves
import com.regolith.domain.playback.StoryPace
import com.regolith.ui.util.formatDate
import com.regolith.ui.components.posterFlight
import com.regolith.ui.library.PictureTile
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.util.formatBytes
import com.regolith.ui.util.formatDateTime
import kotlinx.coroutines.launch

/**
 * The lightbox (the canvas "Images on the Share", Light-Open, Light-Zoom,
 * Light-Inner, Light-More): one picture at a time on black, flown in from
 * the tile that was tapped and back into it when closed. Swipe sideways for
 * its neighbours, pinch or double-tap to zoom, swipe down to send it back.
 *
 * A tap brings the chrome in and out: the name and "3 of 86" at the top,
 * when it was taken and how big it is at the foot, with Set as poster and
 * More (Rename, Move to…, Save to phone, Details, Delete from share). The
 * inner display puts those in the bar and a strip of the album along the
 * bottom, to jump about in it.
 *
 * [onSetPoster] opens Set as poster for the picture on screen, and
 * [onPlayFrom] plays the album as a story from it ("Play from here").
 */
@Composable
fun LightboxScreen(
    viewModel: LightboxViewModel,
    onClose: () -> Unit,
    onSetPoster: (folderId: Long, pictureId: Long) -> Unit,
    onPlayFrom: (folderId: Long, pictureId: Long) -> Unit,
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
        Lightbox(
            state, viewModel.pictureId, onClose,
            actions = PictureActions(
                onPoster = { onSetPoster(it.folderId, it.pictureId) },
                onPlay = { onPlayFrom(it.folderId, it.pictureId) },
                onRename = viewModel::rename,
                onMove = viewModel::move,
                onSave = viewModel::save,
                onDelete = viewModel::delete,
            ),
        )
    }
    // The rename prompt, the delete confirm, the move sheet and their
    // messages: an album's own, for the one picture on screen.
    FileActionsHost(viewModel.fileActions, tagPrefix = "lightbox")
}

/** What can be done to the picture on screen, from the bar and from More. */
private class PictureActions(
    val onPoster: (PictureTile) -> Unit,
    val onPlay: (PictureTile) -> Unit,
    val onRename: (PictureTile) -> Unit,
    val onMove: (PictureTile) -> Unit,
    val onSave: (PictureTile) -> Unit,
    val onDelete: (PictureTile) -> Unit,
)

@Composable
private fun Lightbox(state: LightboxUiState, startId: Long, onClose: () -> Unit, actions: PictureActions) {
    val pictures = state.pictures
    val colors = RegolithTheme.colors
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val wide = LocalWindowShape.current.wide
    val start = remember { pictures.indexOfFirst { it.pictureId == startId }.coerceAtLeast(0) }
    // The pager reads the album through one state, so its page count and its
    // keys always come from the same list. The album changes while it is open
    // (a picture renamed, moved or set as the poster), and lambdas that each
    // captured their own copy disagreed for a frame: a count of 10 asked a
    // list of 9 for its tenth key.
    val latest = rememberUpdatedState(pictures)
    val pager = rememberPagerState(initialPage = start) { latest.value.size }
    val current = pictures.getOrNull(pager.currentPage) ?: pictures.first()
    val zooms = remember { HashMap<Long, PictureZoom>() }
    fun zoomOf(id: Long) = zooms.getOrPut(id) { PictureZoom() }
    var chrome by rememberSaveable { mutableStateOf(true) }
    var details by rememberSaveable { mutableStateOf(false) }
    var more by rememberSaveable { mutableStateOf(false) }
    // Spoof mode and the demo library: nothing here can be changed, so the
    // bar keeps More (for Details) and nothing else.
    val onPoster = if (state.readOnly == null) ({ actions.onPoster(current) }) else null
    // A story plays whatever can be seen, so it is there in spoof mode and the demo too.
    val onPlay = { actions.onPlay(current) }
    val focus = LocalPictureFocus.current

    // --- The swipe down, back into the tile ([SwipeToClose]): it drives the
    // very transition Back plays (lightboxScreen), so the picture flies home
    // under the finger, the black fades, and the album shows through.
    val swipe = rememberSwipeToClose()
    val swiping = swipe?.swiping == true

    // The picture on screen is the one to land in its tile when this closes
    // (PictureFocus), and a picture swiped away goes back to fitted.
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page ->
            val now = latest.value
            focus?.pictureId = now.getOrNull(page)?.pictureId
            now.forEachIndexed { i, p -> if (i != page) zooms[p.pictureId]?.reset() }
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
        val flickPxPerS = with(density) { SwipeToClose.FLICK.toPx() }
        SideEffect {
            swipe?.travelPx = travelPx
            swipe?.flickPxPerS = flickPxPerS
        }
        Box(Modifier.fillMaxSize().background(Color.Black))

        HorizontalPager(
            state = pager,
            key = { page -> latest.value.getOrNull(page)?.pictureId ?: page },
            userScrollEnabled = !currentZoomed && !swiping,
            beyondViewportPageCount = 1,
            pageSpacing = Spacing.s18,
            modifier = Modifier.fillMaxSize().testTag("lightbox_pager"),
        ) { page ->
            val picture = latest.value.getOrNull(page) ?: return@HorizontalPager
            val zoom = zoomOf(picture.pictureId)
            val isCurrent = page == pager.currentPage
            PicturePage(
                picture = picture,
                zoom = zoom,
                // Only the picture on screen flies, or a neighbour just off it
                // would fly from off the screen into its own tile.
                flies = page == pager.settledPage,
                onTap = { chrome = !chrome },
                onEdge = { forward ->
                    val next = page + if (forward) 1 else -1
                    if (next in latest.value.indices) scope.launch { pager.animateScrollToPage(next) }
                },
                // Only the picture on screen, and only when fitted: zoomed in,
                // a drag pans the picture instead.
                modifier = Modifier.swipeToClose(swipe, enabled = isCurrent && !zoom.zoomed, onClosing = { zoom.reset() }),
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
                onPoster = onPoster,
                onPlay = onPlay,
                onMore = { more = true },
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
                onPoster = onPoster,
                onPlay = onPlay,
                onMore = { more = true },
                onJump = { scope.launch { pager.animateScrollToPage(it) } },
            )
        }
    }

    if (more) {
        PictureMoreSheet(
            picture = current,
            album = state.album,
            readOnly = state.readOnly,
            pace = state.pace,
            onDismiss = { more = false },
            onPoster = { more = false; actions.onPoster(current) },
            onPlay = { more = false; actions.onPlay(current) },
            onRename = { more = false; actions.onRename(current) },
            onMove = { more = false; actions.onMove(current) },
            onSave = { more = false; actions.onSave(current) },
            onDetails = { more = false; details = true },
            onDelete = { more = false; actions.onDelete(current) },
        )
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
 * The picture itself, at its own size ([com.regolith.ui.components.WholePicture]).
 * Asked for at twice the screen, at most 4096 across: sharp through a double
 * tap's zoom without decoding a 50 MP photo at full size.
 */
@Composable
private fun WholePicture(picture: PictureTile, pageWidth: Dp, pageHeight: Dp, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val longest = with(density) { maxOf(pageWidth, pageHeight).roundToPx() }
    WholePicture(
        pictureId = picture.pictureId,
        version = picture.version,
        target = (longest * 2).coerceAtMost(MAX_DECODE),
        contentDescription = picture.title,
        modifier = modifier,
    )
}

/** The picture's top bar: close, its name, and where it is in the album; on a wide window, its actions beside. */
@Composable
private fun LightboxTopBar(
    picture: PictureTile,
    position: String,
    wide: Boolean,
    onClose: () -> Unit,
    onPoster: (() -> Unit)?,
    onPlay: () -> Unit,
    onMore: () -> Unit,
) {
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
        if (wide) {
            if (onPoster != null) LightboxAction("Set as poster", R.drawable.rg_ic_set_poster, onPoster, "lightbox_poster_button", inline = true)
            LightboxAction("Play from here", R.drawable.rg_ic_play, onPlay, "lightbox_play_button", inline = true)
            LightboxAction("More", R.drawable.rg_ic_more, onMore, "lightbox_more_button", inline = true, iconOnly = true)
        }
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
    onPoster: (() -> Unit)?,
    onPlay: () -> Unit,
    onMore: () -> Unit,
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
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                if (onPoster != null) LightboxAction("Set as poster", R.drawable.rg_ic_set_poster, onPoster, "lightbox_poster_button")
                LightboxAction("Play from here", R.drawable.rg_ic_play, onPlay, "lightbox_play_button")
                LightboxAction("More", R.drawable.rg_ic_more, onMore, "lightbox_more_button")
            }
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

/** "Taken 14 Jul 2024 · 4032 × 3024 · 4.2 MB": More's line under the picture's name. */
private fun moreFacts(picture: PictureTile): String = listOfNotNull(
    picture.takenAtMs?.let { "Taken ${formatDate(it)}" },
    if (picture.width != null && picture.height != null) "${picture.width} × ${picture.height}" else null,
    formatBytes(picture.sizeBytes),
).joinToString(" · ")

/**
 * One of the lightbox's actions: a frosted tile with its glyph over its
 * name, or a pill on a wide window's bar ([inline]) — a circle with the
 * glyph alone when [iconOnly] (More's three dots).
 */
@Composable
private fun LightboxAction(label: String, icon: Int, onClick: () -> Unit, testTag: String, inline: Boolean = false, iconOnly: Boolean = false) {
    val colors = RegolithTheme.colors
    val shape = if (inline) PillShape else RoundedCornerShape(16.dp)
    val base = Modifier
        .clip(shape)
        .background(Color.White.copy(alpha = 0.06f))
        .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
        .clickable(onClick = onClick)
        .testTag(testTag)
    if (inline && iconOnly) {
        Box(base.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = label, tint = colors.ink, modifier = Modifier.size(18.dp))
        }
    } else if (inline) {
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

/**
 * More (the canvas's Light-More): the picture's name and facts over
 * everything else it can do. On a source that cannot be changed, the reason
 * first, then only Play from here and Details.
 */
@Composable
private fun PictureMoreSheet(
    picture: PictureTile,
    album: String,
    readOnly: String?,
    pace: StoryPace,
    onDismiss: () -> Unit,
    onPoster: () -> Unit,
    onPlay: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onSave: () -> Unit,
    onDetails: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = RegolithTheme.colors
    RegolithSheet(
        title = picture.name,
        onDismiss = onDismiss,
        testTag = "lightbox_more_sheet",
        header = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = Spacing.s12)) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp))) {
                    ArtworkImage(picture.artwork, Modifier.fillMaxSize(), fallbackLabel = picture.name)
                }
                Column(Modifier.padding(start = Spacing.s12).weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
                    Text(picture.name, style = TextStyles.rowLabel, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(moreFacts(picture), style = TextStyles.meta12, color = colors.metadata, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
        },
    ) {
        // Why most of the list is missing, before what is left of it.
        if (readOnly != null) {
            Text(readOnly, style = TextStyles.settingMeta, color = colors.metadata, modifier = Modifier.padding(vertical = Spacing.s12).testTag("lightbox_more_read_only"))
        } else {
            SheetChoice(R.drawable.rg_ic_set_poster, "Set as poster", "Frame it 2:3 for ${album.ifEmpty { "this folder" }}", "lightbox_more_poster", onClick = onPoster)
        }
        SheetChoice(R.drawable.rg_ic_play, "Play from here", "A story from this picture on, ${pace.label} each", "lightbox_more_play", onClick = onPlay)
        if (readOnly == null) {
            SheetChoice(R.drawable.rg_ic_rename, "Rename", picture.name, "lightbox_more_rename", onClick = onRename)
            SheetChoice(R.drawable.rg_ic_folder_go, "Move to…", "Another collection or folder", "lightbox_more_move", onClick = onMove)
            SheetChoice(R.drawable.rg_ic_download, "Save to phone", "Into your gallery, ${PictureSaves.PLACE}", "lightbox_more_save", onClick = onSave)
        }
        SheetChoice(LucideR.drawable.lucide_ic_info, "Details", "Where it lives, the camera and the date", "lightbox_more_details", onClick = onDetails)
        if (readOnly == null) {
            Box(Modifier.fillMaxWidth().padding(vertical = Spacing.s4).height(1.dp).background(colors.hairline))
            // The red belongs to the dialog that decides; here only the glyph.
            SheetChoice(R.drawable.rg_ic_trash, "Delete from share", "Permanent: it is gone from the share", "lightbox_more_delete", tint = colors.accent, onClick = onDelete)
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

/** The picture's box fitted inside [width] × [height] at its [aspect]. */
private fun fit(aspect: Float, width: Dp, height: Dp): Pair<Dp, Dp> {
    val wide = aspect >= width / height
    return if (wide) width to width / aspect else height * aspect to height
}

/** How far down the screen, as a fraction of its height, a pull carries the picture all the way home. */
private const val PULL_TRAVEL = 0.5f

/** How far a zoomed picture is pushed past its side before the swipe goes on to the next one. */
private val EDGE_PUSH = 64.dp

/** The biggest a picture is decoded, on its longer side. */
private const val MAX_DECODE = 4096

/** The strip keeps the picture on screen this many places from its start. */
private const val STRIP_LEAD = 3
